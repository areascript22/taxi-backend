package com.areascript.taxiapp.ride.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.firebase.FirebaseProperties;
import com.areascript.taxiapp.ride.domain.NewRide;
import com.areascript.taxiapp.ride.domain.Ride;
import com.areascript.taxiapp.ride.domain.RideChange;
import com.areascript.taxiapp.ride.domain.RideOutcome;
import com.areascript.taxiapp.ride.domain.RideRepository;
import com.areascript.taxiapp.ride.domain.RideStatus;
import com.areascript.taxiapp.ride.domain.RideTransition;
import com.areascript.taxiapp.ride.domain.ServerTime;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

// Las carreras en Realtime Database: taxi_requests/{passengerId}. Es el único
// lugar que conoce el SDK de Realtime Database, sus transacciones y la forma
// del nodo.
//
// Las dos cosas delicadas del SDK están resueltas acá, una sola vez:
//   - transact: el handler corre sobre datos que pueden estar desactualizados
//     y el SDK lo reejecuta; la regla (RideTransition) es pura.
//   - la primera pasada sobre una referencia "fría" puede entregar null aunque
//     el nodo exista. Si se abortara, el SDK no reintentaría con el valor
//     real; por eso la regla recibe null y devuelve "sin cambios" (ver
//     RideTransitions), y el SDK reintenta solo si el hash no calza.
@Component
public class RealtimeDbRideRepository implements RideRepository {

    private static final Logger log = LoggerFactory.getLogger(RealtimeDbRideRepository.class);
    private static final String RIDES_PATH = "taxi_requests";

    private final FirebaseDatabase database;
    private final long timeoutMillis;

    public RealtimeDbRideRepository(FirebaseDatabase database, FirebaseProperties firebaseProperties) {
        this.database = database;
        this.timeoutMillis = firebaseProperties.operationTimeout().toMillis();
    }

    @Override
    public Optional<Ride> find(String passengerId) {
        DataSnapshot snapshot = read(ref(passengerId), "No se pudo consultar el viaje activo");
        return snapshot != null && snapshot.exists()
                ? Optional.ofNullable(toRide(passengerId, snapshot.getValue()))
                : Optional.empty();
    }

    // Lee todo el nodo y filtra en memoria: lo mismo que se hacía antes, pero
    // en el servidor. Es O(solicitudes activas); la búsqueda por cercanía y un
    // índice propio llegan con la migración a Postgres.
    @Override
    public Optional<Ride> findInProgressOf(String driverUid) {
        DataSnapshot snapshot = read(database.getReference(RIDES_PATH), "No se pudo consultar el viaje activo");
        if (snapshot == null || !snapshot.exists()) {
            return Optional.empty();
        }

        for (DataSnapshot child : snapshot.getChildren()) {
            Ride ride = toRide(child.getKey(), child.getValue());
            if (ride != null && ride.isInProgress() && driverUid.equals(ride.driverId())) {
                return Optional.of(ride);
            }
        }
        return Optional.empty();
    }

    @Override
    public void create(NewRide ride) {
        Map<String, Object> pickup = new HashMap<>();
        pickup.put("latitude", ride.pickup().latitude());
        pickup.put("longitude", ride.pickup().longitude());
        pickup.put("address", ride.pickup().address());
        pickup.put("sector", ride.pickup().sector());

        Map<String, Object> passenger = new HashMap<>();
        passenger.put("name", ride.passengerName());
        passenger.put("profileImage", ride.passengerPhotoUrl());

        Map<String, Object> node = new HashMap<>();
        node.put("rideId", ride.rideId());
        node.put("userId", ride.passengerId());
        node.put("passenger", passenger);
        node.put("pickupLocation", pickup);
        node.put("status", RideStatus.PENDING.wireValue());
        node.put("createdAt", ServerValue.TIMESTAMP);
        node.put("updatedAt", ServerValue.TIMESTAMP);

        CompletableFuture<Void> future = new CompletableFuture<>();
        ref(ride.passengerId()).setValue(node, (error, reference) -> {
            if (error != null) {
                future.completeExceptionally(error.toException());
            } else {
                future.complete(null);
            }
        });

        await(future, "No se pudo crear la solicitud", ride.passengerId());
    }

    @Override
    public RideOutcome transact(String passengerId, RideTransition transition) {
        CompletableFuture<Completion> future = new CompletableFuture<>();
        AtomicReference<RideChange> lastDecision = new AtomicReference<>(RideChange.unchanged());

        ref(passengerId).runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                Ride current = toRide(passengerId, currentData.getValue());
                RideChange change = transition.apply(current);
                lastDecision.set(change);

                return switch (change) {
                    case RideChange.Unchanged unchanged -> Transaction.success(currentData);
                    case RideChange.Rejected rejected -> Transaction.abort();
                    case RideChange.Update update -> {
                        update.fields().forEach((path, value) -> currentData.child(path).setValue(toStored(value)));
                        yield Transaction.success(currentData);
                    }
                    case RideChange.Remove remove -> {
                        currentData.setValue(null);
                        yield Transaction.success(currentData);
                    }
                };
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                future.complete(new Completion(error, committed, currentData));
            }
        });

        Completion completion = await(future, "No se pudo completar la operación sobre la carrera", passengerId);
        if (completion.error() != null) {
            log.error("RideDebug | Firebase rechazó la transacción de passengerId={}: {}",
                    passengerId, completion.error().getMessage());
            throw new ExternalServiceException(
                    "No se pudo completar la operación sobre la carrera", completion.error().toException());
        }

        DataSnapshot snapshot = completion.currentData();
        Ride finalRide = snapshot != null && snapshot.exists()
                ? toRide(passengerId, snapshot.getValue())
                : null;
        return new RideOutcome(completion.committed(), finalRide, lastDecision.get());
    }

    // ---- Mapeo nodo <-> modelo ----

    // El valor de ServerTime.NOW se escribe como la marca de hora del servidor
    // de Firebase; todo lo demás va tal cual.
    private static Object toStored(Object value) {
        return value == ServerTime.NOW ? ServerValue.TIMESTAMP : value;
    }

    // value es el nodo de una carrera (un mapa) o null si no existe.
    @SuppressWarnings("unchecked")
    private static Ride toRide(String passengerId, Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> document = Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) raw));

        Object pickup = document.get("pickupLocation");
        return new Ride(
                passengerId,
                asString(document.get("rideId")),
                RideStatus.parse(document.get("status")).orElse(null),
                asString(path(document, "driver", "data", "id")),
                asString(document.get("cancelledBy")),
                asDouble(pickup instanceof Map<?, ?> p ? p.get("latitude") : null),
                asDouble(pickup instanceof Map<?, ?> p ? p.get("longitude") : null),
                document
        );
    }

    private static Object path(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    private static String asString(Object value) {
        return value instanceof String string ? string : null;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    // ---- Acceso al SDK ----

    private DatabaseReference ref(String passengerId) {
        return database.getReference(RIDES_PATH).child(passengerId);
    }

    // Lectura puntual (no transacción, no listener persistente). El Admin SDK
    // Java no tiene un `.get()` bloqueante como el SDK cliente: se envuelve el
    // listener de una sola vez en un CompletableFuture con timeout.
    private DataSnapshot read(DatabaseReference reference, String failureMessage) {
        CompletableFuture<DataSnapshot> future = new CompletableFuture<>();

        reference.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                future.complete(snapshot);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                future.completeExceptionally(error.toException());
            }
        });

        return await(future, failureMessage, null);
    }

    private <T> T await(CompletableFuture<T> future, String failureMessage, String passengerId) {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido: {} (passengerId={}): {}", failureMessage, passengerId, e.getMessage(), e);
            throw new ExternalServiceException(failureMessage, e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("RideDebug | {} (passengerId={}): {}", failureMessage, passengerId, e.getMessage(), e);
            throw new ExternalServiceException(failureMessage, e);
        }
    }

    private record Completion(DatabaseError error, boolean committed, DataSnapshot currentData) {
    }
}
