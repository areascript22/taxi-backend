package com.areascript.taxiapp.bootstrap;

import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.DriverRole;
import com.areascript.taxiapp.driveradmin.infra.DriverRow;
import com.areascript.taxiapp.driveradmin.infra.VehicleRow;
import com.areascript.taxiapp.passenger.infra.PassengerRow;
import com.google.cloud.Timestamp;

import java.time.Instant;
import java.util.Map;

// Convierte los documentos de Firestore (drivers, vehicles, passengers) en
// filas de Postgres. Es una función pura de (datos del documento, fecha de la
// importación): no lee nada ni escribe nada, así que se prueba sin Firestore
// ni base de datos.
//
// Criterios con los documentos viejos o incompletos (los mismos que ya aplica
// FirestoreDriverRepository al leerlos):
//   * role sin valor o desconocido            -> driver (nunca da privilegios)
//   * approvalStatus sin valor                -> approved (anterior al sistema)
//   * approvalStatus con un valor desconocido -> pending (ante la duda, no aprobado)
//   * rating fuera de 0..5 o sin valor        -> se acota / 5.0
//   * createdAt/updatedAt ausentes            -> la fecha de la importación
//   * fcmToken / language vacíos              -> NULL
final class FirestoreProfileMapper {

    private FirestoreProfileMapper() {
    }

    static DriverRow toDriver(String uid, Map<String, Object> data, Instant importedAt) {
        DriverRow row = new DriverRow(uid);
        row.setFirstName(text(data, "firstName"));
        row.setLastName(text(data, "lastName"));
        row.setEmail(text(data, "email"));
        row.setPhoneNumber(text(data, "phoneNumber"));
        row.setPhotoUrl(nullableText(data, "photoUrl"));
        row.setRating(rating(data.get("rating")));

        row.setRole(DriverRole.fromStored(data.get("role")).wireValue());
        row.setApprovalStatus(ApprovalStatus.fromStored(data.get("approvalStatus")).wireValue());
        row.setApprovedAt(instant(data, "approvedAt"));
        row.setApprovedBy(nullableText(data, "approvedBy"));
        row.setRejectionReason(nullableText(data, "rejectionReason"));
        row.setRejectedAt(instant(data, "rejectedAt"));
        row.setRejectedBy(nullableText(data, "rejectedBy"));

        row.setBlocked(Boolean.TRUE.equals(data.get("isBlocked")));
        row.setBlockReason(nullableText(data, "blockReason"));
        row.setBlockedAt(instant(data, "blockedAt"));
        row.setBlockedBy(nullableText(data, "blockedBy"));

        row.setLanguage(nullableText(data, "language"));
        row.setFcmToken(nullableText(data, "fcmToken"));

        // El vehículo se enlaza después de insertar los vehículos (ver
        // ProfileImportService): la clave foránea exige que ya existan.
        row.setCreatedAt(instantOr(data, "createdAt", importedAt));
        row.setUpdatedAt(instantOr(data, "updatedAt", importedAt));
        return row;
    }

    // Vacío si el documento no dice a qué conductor pertenece: sin dueño no se
    // puede guardar (driver_uid es NOT NULL y clave foránea).
    static VehicleRow toVehicle(String vehicleId, Map<String, Object> data, Instant importedAt) {
        String driverUid = text(data, "driverId");
        if (driverUid.isBlank()) {
            return null;
        }
        VehicleRow row = new VehicleRow(vehicleId, driverUid);
        row.setPlate(text(data, "plate"));
        row.setBrand(text(data, "brand"));
        row.setModel(text(data, "model"));
        row.setYear(data.get("year") instanceof Number number ? number.intValue() : 0);
        row.setColor(text(data, "color"));
        row.setRegistrationNumber(text(data, "registrationNumber"));
        String verification = nullableText(data, "verificationStatus");
        row.setVerificationStatus(verification == null || verification.isBlank() ? "pending" : verification);
        row.setCreatedAt(instantOr(data, "createdAt", importedAt));
        row.setUpdatedAt(instantOr(data, "updatedAt", importedAt));
        return row;
    }

    static PassengerRow toPassenger(String uid, Map<String, Object> data, Instant importedAt) {
        PassengerRow row = new PassengerRow(uid);
        row.setFirstName(text(data, "firstName"));
        row.setLastName(text(data, "lastName"));
        row.setEmail(text(data, "email"));
        row.setPhotoUrl(nullableText(data, "photoUrl"));
        String status = nullableText(data, "status");
        row.setStatus(status == null || status.isBlank() ? "active" : status);
        row.setLanguage(nullableText(data, "language"));
        row.setFcmToken(nullableText(data, "fcmToken"));
        row.setCreatedAt(instantOr(data, "createdAt", importedAt));
        row.setUpdatedAt(instantOr(data, "updatedAt", importedAt));
        return row;
    }

    // ---- Campos ----

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString();
    }

    // Blanco = ausente: un fcmToken "" no es un token.
    private static String nullableText(Map<String, Object> data, String key) {
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
