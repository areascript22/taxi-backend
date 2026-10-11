package com.areascript.taxiapp.bootstrap;

import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.DriverRole;
import com.areascript.taxiapp.user.infra.DriverRow;
import com.areascript.taxiapp.user.infra.VehicleRow;
import com.areascript.taxiapp.user.infra.PassengerRow;
import com.areascript.taxiapp.user.infra.UserRow;
import com.google.cloud.Timestamp;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

// Convierte los documentos de Firestore (drivers, vehicles, passengers) en
// filas de Postgres. Es una función pura de (datos del documento, fecha de la
// importación): no lee nada ni escribe nada, así que se prueba sin Firestore
// ni base de datos.
//
// Un documento de Firestore mezcla identidad y datos del perfil; en Postgres se
// reparten: la identidad va a `users` (compartida por pasajero y conductor) y
// lo propio de cada app a `drivers` / `passengers`.
//
// Criterios con los documentos viejos o incompletos (los mismos que ya aplica
// FirestoreDriverRepository al leerlos):
//   * role sin valor o desconocido            -> user (nunca da privilegios)
//   * role 'driver'                           -> user ("ser conductor" es tener fila en drivers)
//   * approvalStatus sin valor                -> approved (anterior al sistema)
//   * approvalStatus con un valor desconocido -> pending (ante la duda, no aprobado)
//   * rating fuera de 0..5 o sin valor        -> se acota / 5.0
//   * createdAt/updatedAt ausentes            -> la fecha de la importación
//   * fcmToken / language vacíos              -> NULL
//
// Lo que referencia a OTRA persona (blockedBy, approvedBy, rejectedBy) no se
// resuelve acá: en Firestore es un uid de Firebase y en Postgres un users.id;
// la traducción necesita ver todos los usuarios, y la hace ProfileImportService.
final class FirestoreProfileMapper {

    private FirestoreProfileMapper() {
    }

    // ---- users ----

    // Datos de identidad de un conductor: incluye rol y bloqueo.
    static void fillUserFromDriver(UserRow row, Map<String, Object> data, Instant importedAt) {
        fillIdentity(row, data, importedAt);
        row.setRoleCode(roleCode(DriverRole.fromStored(data.get("role"))));
        row.setBlocked(Boolean.TRUE.equals(data.get("isBlocked")));
        row.setBlockReason(nullableText(data, "blockReason"));
        row.setBlockedAt(instant(data, "blockedAt"));
    }

    // Un pasajero no tiene rol ni bloqueo en Firestore: los valores por defecto.
    static void fillUserFromPassenger(UserRow row, Map<String, Object> data, Instant importedAt) {
        fillIdentity(row, data, importedAt);
    }

    private static void fillIdentity(UserRow row, Map<String, Object> data, Instant importedAt) {
        row.setFirstName(text(data, "firstName"));
        row.setLastName(text(data, "lastName"));
        row.setEmail(text(data, "email"));
        row.setCreatedAt(instantOr(data, "createdAt", importedAt));
        row.setUpdatedAt(instantOr(data, "updatedAt", importedAt));
    }

    // El código que existe en la tabla roles. DRIVER ya no es un rol.
    static String roleCode(DriverRole role) {
        return switch (role) {
            case DRIVER -> "user";
            case ADMIN -> "admin";
            case SUPERUSER -> "superuser";
        };
    }

    // ---- drivers ----

    // approvedBy / rejectedBy los completa ProfileImportService.
    static DriverRow toDriver(UUID userId, Map<String, Object> data, Instant importedAt) {
        return fillDriver(new DriverRow(userId), data, importedAt);
    }

    // Completa los campos que vienen de Firestore y NO toca los que no: la
    // presencia (is_online, on_ride) y el vehículo actual, que se enlaza aparte.
    static DriverRow fillDriver(DriverRow row, Map<String, Object> data, Instant importedAt) {
        row.setPhoneNumber(text(data, "phoneNumber"));
        row.setPhotoUrl(nullableText(data, "photoUrl"));
        row.setRating(rating(data.get("rating")));

        row.setApprovalStatus(ApprovalStatus.fromStored(data.get("approvalStatus")).wireValue());
        row.setApprovedAt(instant(data, "approvedAt"));
        row.setRejectionReason(nullableText(data, "rejectionReason"));
        row.setRejectedAt(instant(data, "rejectedAt"));

        row.setLanguage(nullableText(data, "language"));
        row.setFcmToken(nullableText(data, "fcmToken"));

        // El vehículo se enlaza después de insertar los vehículos (ver
        // ProfileImportService): la clave foránea exige que ya existan.
        row.setCreatedAt(instantOr(data, "createdAt", importedAt));
        row.setUpdatedAt(instantOr(data, "updatedAt", importedAt));
        return row;
    }

    // ---- vehicles ----

    // El uid de Firebase del dueño, o null si el documento no lo dice: sin
    // dueño no se puede guardar (driver_id es NOT NULL y clave foránea).
    static String ownerOf(Map<String, Object> data) {
        return nullableText(data, "driverId");
    }

    static VehicleRow toVehicle(String vehicleId, UUID driverId, Map<String, Object> data, Instant importedAt) {
        VehicleRow row = new VehicleRow(vehicleId, driverId);
        row.setPlate(text(data, "plate"));
        row.setBrand(text(data, "brand"));
        row.setModel(text(data, "model"));
        row.setYear(data.get("year") instanceof Number number ? number.intValue() : 0);
        row.setColor(text(data, "color"));
        row.setRegistrationNumber(text(data, "registrationNumber"));
        String verification = nullableText(data, "verificationStatus");
        row.setVerificationStatus(verification == null ? "pending" : verification);
        row.setCreatedAt(instantOr(data, "createdAt", importedAt));
        row.setUpdatedAt(instantOr(data, "updatedAt", importedAt));
        return row;
    }

    // ---- passengers ----

    static PassengerRow toPassenger(UUID userId, Map<String, Object> data, Instant importedAt) {
        PassengerRow row = new PassengerRow(userId);
        row.setPhotoUrl(nullableText(data, "photoUrl"));
        String status = nullableText(data, "status");
        row.setStatus(status == null ? "active" : status);
        row.setLanguage(nullableText(data, "language"));
        row.setFcmToken(nullableText(data, "fcmToken"));
        row.setCreatedAt(instantOr(data, "createdAt", importedAt));
        row.setUpdatedAt(instantOr(data, "updatedAt", importedAt));
        return row;
    }

    // ---- Campos ----

    static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString();
    }

    // Blanco = ausente: un fcmToken "" no es un token.
    static String nullableText(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }

    private static double rating(Object value) {
        if (!(value instanceof Number number)) {
            return 5.0;
        }
        double rating = number.doubleValue();
        if (Double.isNaN(rating)) {
            return 5.0;
        }
        return Math.max(0.0, Math.min(5.0, rating));
    }

    private static Instant instant(Map<String, Object> data, String key) {
        return data.get(key) instanceof Timestamp timestamp ? timestamp.toDate().toInstant() : null;
    }

    private static Instant instantOr(Map<String, Object> data, String key, Instant fallback) {
        Instant value = instant(data, key);
        return value != null ? value : fallback;
    }
}
