package com.areascript.taxiapp.bootstrap;

import com.areascript.taxiapp.user.infra.DriverRow;
import com.areascript.taxiapp.user.infra.VehicleRow;
import com.areascript.taxiapp.user.infra.PassengerRow;
import com.areascript.taxiapp.user.infra.UserRow;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// La traducción documento de Firestore -> filas de Postgres es una función
// pura: se prueba sin Firestore ni base de datos.
class FirestoreProfileMapperTest {

    private static final Instant IMPORTED_AT = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static UserRow newUser() {
        return new UserRow(USER_ID, "firebase-uid");
    }

    @Test
    void la_identidad_de_un_conductor_va_a_users_con_rol_y_bloqueo() {
        Map<String, Object> data = new HashMap<>();
        data.put("firstName", "Ana");
        data.put("lastName", "Pérez");
        data.put("email", "ana@test.com");
        data.put("role", "admin");
        data.put("isBlocked", true);
        data.put("blockReason", "Queja");
        data.put("blockedAt", Timestamp.ofTimeSecondsAndNanos(1_700_000_100L, 0));
        data.put("createdAt", Timestamp.ofTimeSecondsAndNanos(1_600_000_000L, 0));
        UserRow row = newUser();

        FirestoreProfileMapper.fillUserFromDriver(row, data, IMPORTED_AT);

        assertThat(row.getFirebaseUid()).isEqualTo("firebase-uid");
        assertThat(row.getFirstName()).isEqualTo("Ana");
        assertThat(row.getLastName()).isEqualTo("Pérez");
        assertThat(row.getEmail()).isEqualTo("ana@test.com");
        assertThat(row.getRoleCode()).isEqualTo("admin");
        assertThat(row.isBlocked()).isTrue();
        assertThat(row.getBlockReason()).isEqualTo("Queja");
        assertThat(row.getBlockedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_100L));
        assertThat(row.getCreatedAt()).isEqualTo(Instant.ofEpochSecond(1_600_000_000L));
        // Sin updatedAt en el documento: la fecha de la importación.
        assertThat(row.getUpdatedAt()).isEqualTo(IMPORTED_AT);
    }

    @Test
    void ser_conductor_ya_no_es_un_rol() {
        UserRow row = newUser();

        FirestoreProfileMapper.fillUserFromDriver(row, Map.of("role", "driver"), IMPORTED_AT);

        assertThat(row.getRoleCode()).isEqualTo("user");
    }

    @Test
    void un_rol_desconocido_o_ausente_nunca_da_privilegios() {
        UserRow corrupted = newUser();
        UserRow missing = newUser();

        FirestoreProfileMapper.fillUserFromDriver(corrupted, Map.of("role", "root"), IMPORTED_AT);
        FirestoreProfileMapper.fillUserFromDriver(missing, Map.of(), IMPORTED_AT);

        assertThat(corrupted.getRoleCode()).isEqualTo("user");
        assertThat(missing.getRoleCode()).isEqualTo("user");
        assertThat(missing.isBlocked()).isFalse();
    }

    @Test
    void un_pasajero_conserva_el_rol_y_bloqueo_por_defecto() {
        UserRow row = newUser();

        FirestoreProfileMapper.fillUserFromPassenger(
                row, Map.of("firstName", "Luis", "email", "l@test.com"), IMPORTED_AT);

        assertThat(row.getFirstName()).isEqualTo("Luis");
        assertThat(row.getRoleCode()).isEqualTo("user");
        assertThat(row.isBlocked()).isFalse();
    }

    @Test
    void un_conductor_completo_conserva_lo_propio_del_perfil() {
        Map<String, Object> data = new HashMap<>();
        data.put("phoneNumber", "0991112222");
        data.put("photoUrl", "https://p/ana.jpg");
        data.put("rating", 4.5);
        data.put("approvalStatus", "rejected");
        data.put("rejectionReason", "Documentos ilegibles");
        data.put("rejectedAt", Timestamp.ofTimeSecondsAndNanos(1_700_000_000L, 0));
        data.put("language", "en");
        data.put("fcmToken", "tok");

        DriverRow row = FirestoreProfileMapper.toDriver(USER_ID, data, IMPORTED_AT);

        assertThat(row.getUserId()).isEqualTo(USER_ID);
        assertThat(row.getPhoneNumber()).isEqualTo("0991112222");
        assertThat(row.getPhotoUrl()).isEqualTo("https://p/ana.jpg");
        assertThat(row.getRating()).isEqualTo(4.5);
        assertThat(row.getApprovalStatus()).isEqualTo("rejected");
        assertThat(row.getRejectionReason()).isEqualTo("Documentos ilegibles");
        assertThat(row.getRejectedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
        assertThat(row.getLanguage()).isEqualTo("en");
        assertThat(row.getFcmToken()).isEqualTo("tok");
        // Quién aprobó/rechazó lo resuelve ProfileImportService, no el mapper.
        assertThat(row.getApprovedBy()).isNull();
        assertThat(row.getRejectedBy()).isNull();
    }

    @Test
    void un_conductor_anterior_al_sistema_de_aprobacion_queda_aprobado() {
        DriverRow row = FirestoreProfileMapper.toDriver(USER_ID, Map.of(), IMPORTED_AT);

        assertThat(row.getApprovalStatus()).isEqualTo("approved");
        assertThat(row.getRating()).isEqualTo(5.0);
        assertThat(row.getCreatedAt()).isEqualTo(IMPORTED_AT);
    }

    @Test
    void un_estado_de_aprobacion_desconocido_no_aprueba() {
        DriverRow row = FirestoreProfileMapper.toDriver(USER_ID, Map.of("approvalStatus", "maybe"), IMPORTED_AT);

        assertThat(row.getApprovalStatus()).isEqualTo("pending");
    }

    @Test
    void la_calificacion_se_acota_al_rango_que_exige_la_base() {
        assertThat(FirestoreProfileMapper.toDriver(USER_ID, Map.of("rating", 9), IMPORTED_AT).getRating()).isEqualTo(5.0);
        assertThat(FirestoreProfileMapper.toDriver(USER_ID, Map.of("rating", -2), IMPORTED_AT).getRating()).isEqualTo(0.0);
        assertThat(FirestoreProfileMapper.toDriver(USER_ID, Map.of("rating", "alta"), IMPORTED_AT).getRating()).isEqualTo(5.0);
    }

    @Test
    void un_token_o_idioma_vacio_se_guarda_como_ausente() {
        DriverRow row = FirestoreProfileMapper.toDriver(
                USER_ID, Map.of("fcmToken", "", "language", "  "), IMPORTED_AT);

        assertThat(row.getFcmToken()).isNull();
        assertThat(row.getLanguage()).isNull();
    }

    @Test
    void un_vehiculo_sin_dueno_se_detecta() {
        assertThat(FirestoreProfileMapper.ownerOf(Map.of("plate", "PBX-1"))).isNull();
        assertThat(FirestoreProfileMapper.ownerOf(Map.of("driverId", ""))).isNull();
        assertThat(FirestoreProfileMapper.ownerOf(Map.of("driverId", "uid-1"))).isEqualTo("uid-1");
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

        VehicleRow row = FirestoreProfileMapper.toVehicle("veh-1", USER_ID, data, IMPORTED_AT);

        assertThat(row.getVehicleId()).isEqualTo("veh-1");
        assertThat(row.getDriverId()).isEqualTo(USER_ID);
        assertThat(row.getPlate()).isEqualTo("PBX-1234");
        assertThat(row.getYear()).isEqualTo(2020);
        assertThat(row.getRegistrationNumber()).isEmpty();
        assertThat(row.getVerificationStatus()).isEqualTo("pending");
    }

    @Test
    void un_pasajero_sin_estado_queda_activo() {
        PassengerRow row = FirestoreProfileMapper.toPassenger(
                USER_ID, Map.of("language", "es", "photoUrl", "https://p/l.jpg"), IMPORTED_AT);

        assertThat(row.getUserId()).isEqualTo(USER_ID);
        assertThat(row.getStatus()).isEqualTo("active");
        assertThat(row.getLanguage()).isEqualTo("es");
        assertThat(row.getPhotoUrl()).isEqualTo("https://p/l.jpg");
        assertThat(row.getFcmToken()).isNull();
        assertThat(row.getCreatedAt()).isEqualTo(IMPORTED_AT);
    }
}
