package com.areascript.taxiapp.bootstrap;

import com.areascript.taxiapp.driveradmin.infra.DriverRow;
import com.areascript.taxiapp.driveradmin.infra.VehicleRow;
import com.areascript.taxiapp.passenger.infra.PassengerRow;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// La traducción documento de Firestore -> fila de Postgres es una función
// pura: se prueba sin Firestore ni base de datos.
class FirestoreProfileMapperTest {

    private static final Instant IMPORTED_AT = Instant.parse("2026-10-11T12:00:00Z");

    @Test
    void un_conductor_completo_conserva_todos_sus_campos() {
        Map<String, Object> data = new HashMap<>();
        data.put("firstName", "Ana");
        data.put("lastName", "Pérez");
        data.put("email", "ana@test.com");
        data.put("phoneNumber", "0991112222");
        data.put("photoUrl", "https://p/ana.jpg");
        data.put("rating", 4.5);
        data.put("role", "admin");
        data.put("approvalStatus", "rejected");
        data.put("rejectionReason", "Documentos ilegibles");
        data.put("rejectedAt", Timestamp.ofTimeSecondsAndNanos(1_700_000_000L, 0));
        data.put("rejectedBy", "admin-1");
        data.put("isBlocked", true);
        data.put("blockReason", "Queja");
        data.put("blockedAt", Timestamp.ofTimeSecondsAndNanos(1_700_000_100L, 0));
        data.put("blockedBy", "admin-2");
        data.put("language", "en");
        data.put("fcmToken", "tok");
        data.put("createdAt", Timestamp.ofTimeSecondsAndNanos(1_600_000_000L, 0));

        DriverRow row = FirestoreProfileMapper.toDriver("uid-1", data, IMPORTED_AT);

        assertThat(row.getUid()).isEqualTo("uid-1");
        assertThat(row.getFirstName()).isEqualTo("Ana");
        assertThat(row.getLastName()).isEqualTo("Pérez");
        assertThat(row.getRating()).isEqualTo(4.5);
        assertThat(row.getRole()).isEqualTo("admin");
        assertThat(row.getApprovalStatus()).isEqualTo("rejected");
        assertThat(row.getRejectionReason()).isEqualTo("Documentos ilegibles");
        assertThat(row.getRejectedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
        assertThat(row.getRejectedBy()).isEqualTo("admin-1");
        assertThat(row.isBlocked()).isTrue();
        assertThat(row.getBlockReason()).isEqualTo("Queja");
        assertThat(row.getBlockedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_100L));
        assertThat(row.getBlockedBy()).isEqualTo("admin-2");
        assertThat(row.getLanguage()).isEqualTo("en");
        assertThat(row.getFcmToken()).isEqualTo("tok");
        assertThat(row.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(1_600_000_000L));
        // Sin updatedAt en el documento: la fecha de la importación.
        assertThat(row.getUpdatedAt()).isEqualTo(IMPORTED_AT);
    }

    @Test
    void un_conductor_anterior_al_sistema_de_aprobacion_queda_aprobado() {
        DriverRow row = FirestoreProfileMapper.toDriver("old", Map.of("firstName", "Viejo"), IMPORTED_AT);

        assertThat(row.getApprovalStatus()).isEqualTo("approved");
        assertThat(row.getRole()).isEqualTo("driver");
        assertThat(row.isBlocked()).isFalse();
        assertThat(row.getRating()).isEqualTo(5.0);
        assertThat(row.getCreatedAt()).isEqualTo(IMPORTED_AT);
    }

    @Test
    void un_valor_desconocido_nunca_da_privilegios_ni_aprueba() {
        Map<String, Object> data = Map.of("role", "root", "approvalStatus", "maybe");

        DriverRow row = FirestoreProfileMapper.toDriver("x", data, IMPORTED_AT);

        assertThat(row.getRole()).as("rol corrupto = conductor corriente").isEqualTo("driver");
        assertThat(row.getApprovalStatus()).as("estado corrupto = no aprobado").isEqualTo("pending");
    }

    @Test
    void la_calificacion_se_acota_al_rango_que_exige_la_base() {
        assertThat(FirestoreProfileMapper.toDriver("a", Map.of("rating", 9), IMPORTED_AT).getRating()).isEqualTo(5.0);
        assertThat(FirestoreProfileMapper.toDriver("b", Map.of("rating", -2), IMPORTED_AT).getRating()).isEqualTo(0.0);
        assertThat(FirestoreProfileMapper.toDriver("c", Map.of("rating", "alta"), IMPORTED_AT).getRating()).isEqualTo(5.0);
    }

    @Test
    void un_token_o_idioma_vacio_se_guarda_como_ausente() {
        DriverRow row = FirestoreProfileMapper.toDriver("x", Map.of("fcmToken", "", "language", "  "), IMPORTED_AT);

        assertThat(row.getFcmToken()).isNull();
        assertThat(row.getLanguage()).isNull();
    }

    @Test
    void un_vehiculo_sin_dueno_no_se_puede_guardar() {
        assertThat(FirestoreProfileMapper.toVehicle("v", Map.of("plate", "PBX-1"), IMPORTED_AT)).isNull();
        assertThat(FirestoreProfileMapper.toVehicle("v", Map.of("driverId", ""), IMPORTED_AT)).isNull();
    }

    @Test
    void un_vehiculo_toma_sus_campos_y_valores_por_defecto() {
        Map<String, Object> data = Map.of(
                "driverId", "uid-1",
                "plate", "PBX-1234",
                "brand", "Toyota",
                "model", "Corolla",
                "year", 2020L,
                "color", "Amarillo");

        VehicleRow row = FirestoreProfileMapper.toVehicle("veh-1", data, IMPORTED_AT);

        assertThat(row.getVehicleId()).isEqualTo("veh-1");
        assertThat(row.getDriverUid()).isEqualTo("uid-1");
        assertThat(row.getPlate()).isEqualTo("PBX-1234");
        assertThat(row.getYear()).isEqualTo(2020);
        assertThat(row.getRegistrationNumber()).isEmpty();
        assertThat(row.getVerificationStatus()).isEqualTo("pending");
    }

    @Test
    void un_pasajero_sin_estado_queda_activo() {
        PassengerRow row = FirestoreProfileMapper.toPassenger(
                "p-1", Map.of("firstName", "Luis", "email", "l@test.com", "language", "es"), IMPORTED_AT);

        assertThat(row.getFirstName()).isEqualTo("Luis");
        assertThat(row.getStatus()).isEqualTo("active");
        assertThat(row.getLanguage()).isEqualTo("es");
        assertThat(row.getFcmToken()).isNull();
        assertThat(row.getCreatedAt()).isEqualTo(IMPORTED_AT);
    }
}
