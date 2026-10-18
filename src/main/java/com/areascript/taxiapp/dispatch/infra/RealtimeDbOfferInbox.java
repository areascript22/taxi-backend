package com.areascript.taxiapp.dispatch.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.firebase.FirebaseProperties;
import com.areascript.taxiapp.dispatch.domain.Offer;
import com.areascript.taxiapp.dispatch.domain.OfferInbox;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// La bandeja de ofertas de cada conductor en Realtime Database:
//
//   driver_offers/{driverUid}/{passengerId} = { rideId, passengerName, pickup, ... }
//
// Se escribe SOLO desde el servidor, y cada conductor lee únicamente su rama:
// las reglas de la base tienen que decir
//
//   "driver_offers": { "$uid": { ".read": "auth.uid === $uid", ".write": false } }
//
// (las reglas de Realtime Database todavía no están versionadas en el repo).
//
// Está en Realtime Database y no en Postgres porque las apps escuchan cambios
// en vivo sobre su rama, igual que con las carreras.
@Component
public class RealtimeDbOfferInbox implements OfferInbox {

    private static final Logger log = LoggerFactory.getLogger(RealtimeDbOfferInbox.class);
    private static final String INBOX_PATH = "driver_offers";

    private final FirebaseDatabase database;
    private final long timeoutMillis;

    public RealtimeDbOfferInbox(FirebaseDatabase database, FirebaseProperties firebaseProperties) {
        this.database = database;
        this.timeoutMillis = firebaseProperties.operationTimeout().toMillis();
    }

    @Override
    public void offer(String driverUid, Offer offer) {
        Map<String, Object> pickup = new HashMap<>();
        pickup.put("latitude", offer.pickupLatitude());
        pickup.put("longitude", offer.pickupLongitude());
        pickup.put("address", offer.pickupAddress());
        pickup.put("sector", offer.pickupSector());

        Map<String, Object> node = new HashMap<>();
        node.put("rideId", offer.rideId());
        node.put("passengerId", offer.passengerId());
        node.put("passengerName", offer.passengerName());
        node.put("pickup", pickup);
        node.put("ring", offer.ring());
        node.put("distanceMeters", offer.distanceMeters());
        node.put("expiresAt", offer.expiresAt().toEpochMilli());

        write(ref(driverUid, offer.passengerId()), node,
                "No se pudo dejar la oferta en la bandeja de " + driverUid);
    }

    @Override
    public void withdraw(String driverUid, String passengerId) {
        write(ref(driverUid, passengerId), null,
                "No se pudo retirar la oferta de la bandeja de " + driverUid);
    }

    @Override
    public boolean wasOffered(String driverUid, String passengerId, String rideId) {
        CompletableFuture<DataSnapshot> future = new CompletableFuture<>();
        ref(driverUid, passengerId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                future.complete(snapshot);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                future.completeExceptionally(error.toException());
            }
        });

        try {
            DataSnapshot snapshot = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return snapshot != null
                    && snapshot.exists()
                    && rideId.equals(snapshot.child("rideId").getValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("No se pudo consultar la bandeja de " + driverUid, e);
        } catch (ExecutionException | TimeoutException e) {
            throw failure("No se pudo consultar la bandeja de " + driverUid, e);
        }
    }

    private DatabaseReference ref(String driverUid, String passengerId) {
        return database.getReference(INBOX_PATH).child(driverUid).child(passengerId);
    }

    // setValue(valor, listener) y no setValueAsync: así se espera la confirmación
    // de la base (con timeout) y un valor null borra el nodo.
    private void write(DatabaseReference reference, Object value, String message) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        reference.setValue(value, (error, ref) -> {
            if (error != null) {
                done.completeExceptionally(error.toException());
            } else {
                done.complete(null);
            }
        });
        try {
            done.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(message, e);
        } catch (ExecutionException | TimeoutException e) {
            throw failure(message, e);
        }
    }
    private static ExternalServiceException failure(String message, Exception cause) {
        log.error("DispatchDebug | {}: {}", message, cause.getMessage(), cause);
        return new ExternalServiceException(message, cause);
    }
}
