package com.areascript.taxiapp.driveradmin.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.firebase.FirebaseProperties;
import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.Driver;
import com.areascript.taxiapp.driveradmin.domain.DriverPage;
import com.areascript.taxiapp.driveradmin.domain.DriverRepository;
import com.areascript.taxiapp.driveradmin.domain.DriverRole;
import com.areascript.taxiapp.driveradmin.domain.Vehicle;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Conductores y vehículos en Firestore (colecciones "drivers" y "vehicles").
// Es el único lugar que conoce los nombres de los campos y cómo se guardan.
@Component
public class FirestoreDriverRepository implements DriverRepository {

    private static final Logger log = LoggerFactory.getLogger(FirestoreDriverRepository.class);
    private static final String DRIVERS_COLLECTION = "drivers";
    private static final String VEHICLES_COLLECTION = "vehicles";

    private final Firestore firestore;
    private final long timeoutMillis;

    public FirestoreDriverRepository(Firestore firestore, FirebaseProperties firebaseProperties) {
        this.firestore = firestore;
        this.timeoutMillis = firebaseProperties.operationTimeout().toMillis();
    }

    @Override
    public Optional<Driver> findById(String uid) {
        DocumentSnapshot snapshot = await(
                firestore.collection(DRIVERS_COLLECTION).document(uid).get(),
                "No se pudo obtener el conductor uid=" + uid);
        if (!snapshot.exists()) {
            return Optional.empty();
        }
        return Optional.of(toDriver(snapshot));
    }

    @Override
    public DriverPage findAll() {
        List<QueryDocumentSnapshot> documents = await(
                firestore.collection(DRIVERS_COLLECTION).get(),
                "No se pudo obtener la lista de drivers").getDocuments();

        List<Driver> drivers = new ArrayList<>();
        for (QueryDocumentSnapshot document : documents) {
            drivers.add(toDriver(document));
        }
        return new DriverPage(drivers, null, false);
    }

    // Paginación real por cursor (Firestore no soporta offset eficiente):
    // se ordena por el id del documento, que siempre existe en todo
    // documento -- a diferencia de, por ejemplo, 'createdAt', que algún
    // documento viejo podría no tener, y Firestore excluye silenciosamente
    // de un orderBy a los documentos que no tienen el campo indicado.
    // pageSize+1 se pide de más para saber si hay una página siguiente sin
    // necesidad de un segundo viaje al servidor.
    @Override
    public DriverPage findPage(int pageSize, String cursor) {
        Query query = firestore.collection(DRIVERS_COLLECTION)
                .orderBy(FieldPath.documentId())
                .limit(pageSize + 1);
        if (cursor != null && !cursor.isBlank()) {
            query = query.startAfter(cursor);
        }

        List<QueryDocumentSnapshot> documents =
                await(query.get(), "No se pudo obtener la página de drivers").getDocuments();
        boolean hasMore = documents.size() > pageSize;
        List<QueryDocumentSnapshot> pageDocuments = hasMore ? documents.subList(0, pageSize) : documents;

        List<Driver> drivers = new ArrayList<>();
        for (QueryDocumentSnapshot document : pageDocuments) {
            drivers.add(toDriver(document));
        }

        String nextCursor = (hasMore && !pageDocuments.isEmpty())
                ? pageDocuments.get(pageDocuments.size() - 1).getId()
                : null;
        return new DriverPage(drivers, nextCursor, hasMore);
    }

    @Override
    public void updateRole(String uid, DriverRole role) {
        await(
                firestore.collection(DRIVERS_COLLECTION).document(uid).update("role", role.wireValue()),
                "No se pudo actualizar el campo 'role' de uid=" + uid);
    }

    @Override
    public void ensureRole(String uid, DriverRole role) {
        await(
                firestore.collection(DRIVERS_COLLECTION).document(uid)
                        .set(Map.of("role", role.wireValue()), SetOptions.merge()),
                "No se pudo sincronizar el campo 'role' de uid=" + uid);
    }

    @Override
    public void applyApproval(String uid, ApprovalStatus status, String reason, String actorUid) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("approvalStatus", status.wireValue());
        if (status == ApprovalStatus.APPROVED) {
            updates.put("approvedAt", Timestamp.now());
            updates.put("approvedBy", actorUid);
            updates.put("rejectionReason", FieldValue.delete());
        } else if (status == ApprovalStatus.REJECTED) {
            updates.put("rejectionReason", reason);
            updates.put("rejectedAt", Timestamp.now());
            updates.put("rejectedBy", actorUid);
        }

        await(
                firestore.collection(DRIVERS_COLLECTION).document(uid).update(updates),
                "No se pudo actualizar el estado de aprobación de uid=" + uid);
    }

    @Override
    public void applyBlock(String uid, boolean blocked, String reason, String actorUid) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("isBlocked", blocked);
        if (blocked) {
            updates.put("blockReason", reason);
            updates.put("blockedAt", Timestamp.now());
            updates.put("blockedBy", actorUid);
        } else {
            updates.put("blockReason", FieldValue.delete());
            updates.put("blockedAt", FieldValue.delete());
            updates.put("blockedBy", FieldValue.delete());
        }

        await(
                firestore.collection(DRIVERS_COLLECTION).document(uid).update(updates),
                "No se pudo actualizar el estado de bloqueo de uid=" + uid);
    }

    // ---- Mapeo documento -> modelo ----

    private Driver toDriver(DocumentSnapshot document) {
        Map<String, Object> data = document.getData() == null ? Map.of() : document.getData();
        String vehicleId = stringField(data, "vehicleId");
        Vehicle vehicle = vehicleId.isBlank() ? null : findVehicle(vehicleId);

        return new Driver(
                document.getId(),
                stringField(data, "firstName"),
                stringField(data, "lastName"),
                stringField(data, "email"),
                stringField(data, "phoneNumber"),
                nullableString(data, "photoUrl"),
                stringField(data, "fcmToken"),
                numberField(data, "rating", 5.0),
                DriverRole.fromStored(data.get("role")),
                ApprovalStatus.fromStored(data.get("approvalStatus")),
                Boolean.TRUE.equals(data.get("isBlocked")),
                nullableString(data, "blockReason"),
                instantField(data, "blockedAt"),
                nullableString(data, "blockedBy"),
                nullableString(data, "rejectionReason"),
                instantField(data, "createdAt"),
                instantField(data, "updatedAt"),
                vehicle
        );
    }

    private Vehicle findVehicle(String vehicleId) {
        DocumentSnapshot snapshot = await(
                firestore.collection(VEHICLES_COLLECTION).document(vehicleId).get(),
                "No se pudo obtener el vehículo " + vehicleId);
        if (!snapshot.exists() || snapshot.getData() == null) {
            return null;
        }

        Map<String, Object> data = snapshot.getData();
        Object verificationStatus = data.get("verificationStatus");
        return new Vehicle(
                snapshot.getId(),
                stringField(data, "driverId"),
                stringField(data, "plate"),
                stringField(data, "brand"),
                stringField(data, "model"),
                (int) numberField(data, "year", 0),
                stringField(data, "color"),
                stringField(data, "registrationNumber"),
                verificationStatus == null ? "pending" : verificationStatus.toString(),
                instantField(data, "createdAt"),
                instantField(data, "updatedAt")
        );
    }

    private static String stringField(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString();
    }

    private static String nullableString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? null : value.toString();
    }

    private static double numberField(Map<String, Object> data, String key, double defaultValue) {
        Object value = data.get(key);
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    private static Instant instantField(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof Timestamp timestamp ? timestamp.toDate().toInstant() : null;
    }

    // Espera un resultado de Firestore con el timeout de Firebase y traduce
    // cualquier fallo a ExternalServiceException.
    private <T> T await(ApiFuture<T> future, String failureMessage) {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido: {}: {}", failureMessage, e.getMessage(), e);
            throw new ExternalServiceException(failureMessage, e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("DriverAdminDebug | {}: {}", failureMessage, e.getMessage(), e);
            throw new ExternalServiceException(failureMessage, e);
        }
    }
}
