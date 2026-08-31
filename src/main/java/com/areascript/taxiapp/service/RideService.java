package com.areascript.taxiapp.service;

import com.areascript.taxiapp.dto.PushNotificationDTO;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);
    private static final String TAXI_REQUESTS_PATH = "taxi_requests";
    private static final String PASSENGERS_COLLECTION = "passengers";
    private static final String DRIVERS_COLLECTION = "drivers";
    private static final double EARTH_RADIUS_METERS = 6371000.0;

    // Rutas de push registradas en cada app (ver PushNotificationsService):
    // deben apuntar a una pantalla que no requiera un objeto `extra`, porque
    // el payload de un push no puede llevar objetos Dart.
    private static final String PASSENGER_PUSH_ROUTE = "/ride_tracking";
    private static final String DRIVER_PUSH_ROUTE = "/booking";

    // Margen para que ambas apps -que escuchan status vía onValue sobre este
    // mismo nodo- alcancen a recibir y procesar el status terminal (y
    // driver_app/passenger_app puedan reaccionar: diálogo de cancelación o
    // navegación al finalizar) antes de que el nodo desaparezca. Solo
    // entonces se limpia de Realtime Database.
    private static final long RIDE_CLEANUP_DELAY_SECONDS = 10;
    private static final Set<String> TERMINAL_STATUSES = Set.of("cancelled", "tripCompleted");

    private enum OperationAbortReason { FORBIDDEN, NOT_ALLOWED }

    private final FirebaseDatabase firebaseDatabase;
    private final Firestore firestore;
    private final NotificationService notificationService;

    public RideService(FirebaseDatabase firebaseDatabase, Firestore firestore, NotificationService notificationService) {
        this.firebaseDatabase = firebaseDatabase;
        this.firestore = firestore;
        this.notificationService = notificationService;
    }

    // Reemplaza la transacción que antes corría en el driver_app directo
    // sobre Realtime Database: mueve la asignación atómica del driver acá
    // para que, si dos conductores presionan "aceptar" al mismo tiempo, solo
    // uno gane sin importar cuál app llegó primero al cliente.
    public void acceptRide(
            String passengerId,
            String driverUid,
            String driverEmail,
            String driverDisplayName,
            String driverPhotoUrl,
            double driverLatitude,
            double driverLongitude
    ) {
        DatabaseReference rideRef = firebaseDatabase.getReference(TAXI_REQUESTS_PATH).child(passengerId);
        CompletableFuture<RideTransactionOutcome> future = new CompletableFuture<>();

        rideRef.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                if (currentData.getValue() == null) {
                    // El Admin SDK puede invocar este handler con datos aún no
                    // sincronizados en la primera pasada sobre una referencia
                    // "fría" (recién creada, sin listener previo), devolviendo
                    // null aunque el nodo sí exista en el servidor. Si
                    // abortamos acá, cancelamos la transacción sin darle
                    // chance al SDK de reintentar con el valor real. En vez de
                    // eso, dejamos pasar sin modificar nada: si el nodo existe
                    // de verdad, el hash no calzará con el servidor y el SDK
                    // reintenta automáticamente este mismo callback con los
                    // datos reales; si de verdad no existe, el commit no hará
                    // ningún cambio y lo detectamos después de la transacción.
                    return Transaction.success(currentData);
                }

                if (!"pending".equals(currentData.child("status").getValue())) {
                    return Transaction.abort();
                }

                currentData.child("status").setValue("driverAssigned");
                currentData.child("updatedAt").setValue(ServerValue.TIMESTAMP);

                Map<String, Object> driverData = new HashMap<>();
                driverData.put("id", driverUid);
                driverData.put("email", driverEmail);
                driverData.put("displayName", driverDisplayName);
                driverData.put("photoUrl", driverPhotoUrl);
                currentData.child("driver").child("data").setValue(driverData);

                Map<String, Object> location = new HashMap<>();
                location.put("latitude", driverLatitude);
                location.put("longitude", driverLongitude);
                currentData.child("driver").child("location").setValue(location);

                Double pickupLat = asDouble(currentData.child("pickupLocation").child("latitude").getValue());
                Double pickupLng = asDouble(currentData.child("pickupLocation").child("longitude").getValue());
                Double initialDistance = (pickupLat != null && pickupLng != null)
                        ? haversineMeters(driverLatitude, driverLongitude, pickupLat, pickupLng)
                        : null;
                currentData.child("driver").child("initialDistance").setValue(initialDistance);

                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                future.complete(new RideTransactionOutcome(error, committed, currentData));
            }
        });

        RideTransactionOutcome outcome;
        try {
            outcome = future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido al aceptar la carrera de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideAcceptException("No se pudo aceptar la carrera", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("RideDebug | Error al aceptar la carrera de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideAcceptException("No se pudo aceptar la carrera", e);
        }

        if (outcome.error() != null) {
            log.error("RideDebug | Firebase rechazó la transacción de passengerId={}: {}", passengerId, outcome.error().getMessage());
            throw new RideAcceptException("No se pudo aceptar la carrera", outcome.error().toException());
        }

        DataSnapshot snapshot = outcome.currentData();
        if (snapshot == null || !snapshot.exists()) {
            // Cubre tanto el caso real de "nunca existió" como el commit sin
            // cambios que hacemos cuando doTransaction ve currentData nulo.
            throw new RideNotFoundException(passengerId);
        }

        String finalStatus = (String) snapshot.child("status").getValue();
        String assignedDriverId = (String) snapshot.child("driver").child("data").child("id").getValue();
        boolean assignedToThisDriver = "driverAssigned".equals(finalStatus) && driverUid.equals(assignedDriverId);

        if (!outcome.committed() || !assignedToThisDriver) {
            throw new RideAlreadyAssignedException(passengerId);
        }

        log.info("RideDebug | Carrera de passengerId={} aceptada por driverUid={}", passengerId, driverUid);
        notifyByFcm(
                PASSENGERS_COLLECTION,
                passengerId,
                new PushNotificationDTO(
                        "¡Carrera aceptada!",
                        "Un conductor va en camino a recogerte",
                        PASSENGER_PUSH_ROUTE
                )
        );
    }

    // Reemplaza los `.update({'status': 'cancelled', ...})` que antes hacían
    // driver_app y passenger_app directo sobre Realtime Database: se mueve
    // acá para (a) verificar con el token de Firebase que quien cancela es
    // realmente el pasajero dueño de la carrera o el conductor ya asignado
    // -no un tercero-, y (b) poder avisarle a la otra parte por push, algo
    // que solo el backend puede hacer (el Admin SDK de FCM no está expuesto
    // al cliente).
    public void cancelRide(String passengerId, String callerUid) {
        DatabaseReference rideRef = firebaseDatabase.getReference(TAXI_REQUESTS_PATH).child(passengerId);
        CompletableFuture<RideTransactionOutcome> future = new CompletableFuture<>();
        AtomicReference<OperationAbortReason> abortReason = new AtomicReference<>();

        rideRef.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                if (currentData.getValue() == null) {
                    // Mismo caso de referencia "fría" que en acceptRide: no
                    // abortamos, dejamos que el SDK reintente con el valor
                    // real si el nodo sí existe.
                    return Transaction.success(currentData);
                }

                String status = (String) currentData.child("status").getValue();
                if (TERMINAL_STATUSES.contains(status)) {
                    abortReason.set(OperationAbortReason.NOT_ALLOWED);
                    return Transaction.abort();
                }

                String assignedDriverId = (String) currentData.child("driver").child("data").child("id").getValue();
                String cancelledBy;
                if (callerUid.equals(passengerId)) {
                    cancelledBy = "passenger";
                } else if (callerUid.equals(assignedDriverId)) {
                    cancelledBy = "driver";
                } else {
                    abortReason.set(OperationAbortReason.FORBIDDEN);
                    return Transaction.abort();
                }

                currentData.child("status").setValue("cancelled");
                currentData.child("cancelledBy").setValue(cancelledBy);
                currentData.child("updatedAt").setValue(ServerValue.TIMESTAMP);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                future.complete(new RideTransactionOutcome(error, committed, currentData));
            }
        });

        RideTransactionOutcome outcome;
        try {
            outcome = future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido al cancelar la carrera de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideCancelException("No se pudo cancelar la carrera", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("RideDebug | Error al cancelar la carrera de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideCancelException("No se pudo cancelar la carrera", e);
        }

        if (outcome.error() != null) {
            log.error("RideDebug | Firebase rechazó la transacción de cancelación de passengerId={}: {}", passengerId, outcome.error().getMessage());
            throw new RideCancelException("No se pudo cancelar la carrera", outcome.error().toException());
        }

        DataSnapshot snapshot = outcome.currentData();
        if (snapshot == null || !snapshot.exists()) {
            throw new RideNotFoundException(passengerId);
        }

        if (abortReason.get() == OperationAbortReason.FORBIDDEN) {
            throw new RideForbiddenException(passengerId);
        }

        String finalStatus = (String) snapshot.child("status").getValue();
        if (!outcome.committed() || !"cancelled".equals(finalStatus)) {
            throw new RideAlreadyFinishedException(passengerId);
        }

        String cancelledBy = (String) snapshot.child("cancelledBy").getValue();
        log.info("RideDebug | Carrera de passengerId={} cancelada por callerUid={} (rol={})", passengerId, callerUid, cancelledBy);

        if ("passenger".equals(cancelledBy)) {
            String assignedDriverId = (String) snapshot.child("driver").child("data").child("id").getValue();
            if (assignedDriverId != null) {
                notifyByFcm(
                        DRIVERS_COLLECTION,
                        assignedDriverId,
                        new PushNotificationDTO(
                                "Carrera cancelada",
                                "El pasajero canceló la carrera",
                                DRIVER_PUSH_ROUTE
                        )
                );
            }
        } else {
            notifyByFcm(
                    PASSENGERS_COLLECTION,
                    passengerId,
                    new PushNotificationDTO(
                            "Viaje cancelado",
                            "Tu conductor canceló el viaje",
                            PASSENGER_PUSH_ROUTE
                    )
            );
        }

        scheduleRideCleanup(rideRef, passengerId);
    }

    // Reemplaza el `.update({'status': 'tripCompleted', ...})` que antes
    // hacía driver_app directo sobre Realtime Database: se mueve acá para
    // verificar que quien finaliza es el conductor realmente asignado, y
    // para poder reutilizar la misma limpieza programada del nodo que usa
    // cancelRide (corre en el backend, así que no depende de que la app
    // siga abierta el tiempo suficiente).
    public void completeTrip(String passengerId, String driverUid) {
        DatabaseReference rideRef = firebaseDatabase.getReference(TAXI_REQUESTS_PATH).child(passengerId);
        CompletableFuture<RideTransactionOutcome> future = new CompletableFuture<>();
        AtomicReference<OperationAbortReason> abortReason = new AtomicReference<>();

        rideRef.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                if (currentData.getValue() == null) {
                    return Transaction.success(currentData);
                }

                String status = (String) currentData.child("status").getValue();
                // Solo se puede finalizar un viaje que el pasajero ya
                // confirmó que arrancó (botón "He llegado" -> pasajero
                // confirma "en camino" -> tripStarted). TripScreen solo
                // muestra "Finalizar viaje" en ese status, pero igual se
                // valida acá porque el cliente no es fuente de verdad.
                if (!"tripStarted".equals(status)) {
                    abortReason.set(OperationAbortReason.NOT_ALLOWED);
                    return Transaction.abort();
                }

                String assignedDriverId = (String) currentData.child("driver").child("data").child("id").getValue();
                if (!driverUid.equals(assignedDriverId)) {
                    abortReason.set(OperationAbortReason.FORBIDDEN);
                    return Transaction.abort();
                }

                currentData.child("status").setValue("tripCompleted");
                currentData.child("updatedAt").setValue(ServerValue.TIMESTAMP);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                future.complete(new RideTransactionOutcome(error, committed, currentData));
            }
        });

        RideTransactionOutcome outcome;
        try {
            outcome = future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido al finalizar el viaje de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideCompleteException("No se pudo finalizar el viaje", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("RideDebug | Error al finalizar el viaje de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideCompleteException("No se pudo finalizar el viaje", e);
        }

        if (outcome.error() != null) {
            log.error("RideDebug | Firebase rechazó la transacción de finalización de passengerId={}: {}", passengerId, outcome.error().getMessage());
            throw new RideCompleteException("No se pudo finalizar el viaje", outcome.error().toException());
        }

        DataSnapshot snapshot = outcome.currentData();
        if (snapshot == null || !snapshot.exists()) {
            throw new RideNotFoundException(passengerId);
        }

        if (abortReason.get() == OperationAbortReason.FORBIDDEN) {
            throw new RideForbiddenException(passengerId);
        }

        String finalStatus = (String) snapshot.child("status").getValue();
        if (!outcome.committed() || !"tripCompleted".equals(finalStatus)) {
            throw new RideAlreadyFinishedException(passengerId);
        }

        log.info("RideDebug | Viaje de passengerId={} finalizado por driverUid={}", passengerId, driverUid);
        scheduleRideCleanup(rideRef, passengerId);
    }

    // Borra el nodo taxi_requests/{passengerId} unos segundos después de
    // llegar a un status terminal (cancelado o finalizado), una vez que
    // ambos clientes ya tuvieron tiempo de recibir ese status por su
    // listener de Firebase. Corre en una transacción propia (no un
    // removeValue directo) para no pisar una solicitud nueva que el
    // pasajero pudiera haber creado en esa misma key mientras tanto: solo
    // borra si el status sigue siendo terminal.
    private void scheduleRideCleanup(DatabaseReference rideRef, String passengerId) {
        CompletableFuture.runAsync(() -> rideRef.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                if (currentData.getValue() == null) {
                    return Transaction.success(currentData);
                }
                if (TERMINAL_STATUSES.contains(currentData.child("status").getValue())) {
                    currentData.setValue(null);
                }
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                if (error != null) {
                    log.error("RideDebug | No se pudo limpiar el nodo de passengerId={} tras finalizar/cancelar: {}", passengerId, error.getMessage());
                }
            }
        }), CompletableFuture.delayedExecutor(RIDE_CLEANUP_DELAY_SECONDS, TimeUnit.SECONDS));
    }

    private void notifyByFcm(String collection, String documentId, PushNotificationDTO payload) {
        try {
            DocumentSnapshot snapshot = firestore.collection(collection).document(documentId).get().get();
            if (!snapshot.exists()) {
                log.warn("RideDebug | No se encontró el documento {}/{} para notificarle", collection, documentId);
                return;
            }

            notificationService.sendPush(snapshot.getString("fcmToken"), payload);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido al notificar a {}/{}: {}", collection, documentId, e.getMessage(), e);
        } catch (ExecutionException e) {
            log.error("RideDebug | Error al notificar a {}/{}: {}", collection, documentId, e.getMessage(), e);
        }
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    private record RideTransactionOutcome(DatabaseError error, boolean committed, DataSnapshot currentData) {
    }
}
