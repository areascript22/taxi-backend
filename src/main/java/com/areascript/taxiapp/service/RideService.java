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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);
    private static final String TAXI_REQUESTS_PATH = "taxi_requests";
    private static final String PASSENGERS_COLLECTION = "passengers";
    private static final double EARTH_RADIUS_METERS = 6371000.0;

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
                    return Transaction.abort();
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

        if (!outcome.committed()) {
            DataSnapshot snapshot = outcome.currentData();
            if (snapshot == null || !snapshot.exists()) {
                throw new RideNotFoundException(passengerId);
            }
            throw new RideAlreadyAssignedException(passengerId);
        }

        log.info("RideDebug | Carrera de passengerId={} aceptada por driverUid={}", passengerId, driverUid);
        notifyPassenger(passengerId);
    }

    private void notifyPassenger(String passengerId) {
        try {
            DocumentSnapshot snapshot = firestore.collection(PASSENGERS_COLLECTION).document(passengerId).get().get();
            if (!snapshot.exists()) {
                log.warn("RideDebug | No se encontró el documento de passengerId={} para notificarle", passengerId);
                return;
            }

            notificationService.sendPush(
                    snapshot.getString("fcmToken"),
                    new PushNotificationDTO(
                            "¡Carrera aceptada!",
                            "Un conductor va en camino a recogerte",
                            "/ride_tracking"
                    )
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido al notificar al passengerId={}: {}", passengerId, e.getMessage(), e);
        } catch (ExecutionException e) {
            log.error("RideDebug | Error al notificar al passengerId={} tras aceptar la carrera: {}", passengerId, e.getMessage(), e);
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
