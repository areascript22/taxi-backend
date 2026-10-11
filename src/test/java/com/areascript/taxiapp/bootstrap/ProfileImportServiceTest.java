package com.areascript.taxiapp.bootstrap;

import com.areascript.taxiapp.database.support.SharedPostgis;
import com.areascript.taxiapp.user.infra.DriverRow;
import com.areascript.taxiapp.user.infra.DriverRowRepository;
import com.areascript.taxiapp.user.infra.VehicleRowRepository;
import com.areascript.taxiapp.user.infra.PassengerRowRepository;
import com.areascript.taxiapp.user.infra.UserRow;
import com.areascript.taxiapp.user.infra.UserRowRepository;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// La copia Firestore -> Postgres contra un Postgres REAL (el mismo
// postgis/postgis que compose.yaml), con las migraciones de Flyway aplicadas y
// Hibernate en ddl-auto=validate: si una entidad no coincide con el esquema,
// el contexto ni arranca. Cada test corre en una transacción que se deshace.
//
// Se omite si Docker no está disponible.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIf("dockerAvailable")
class ProfileImportServiceTest {

    private static final Instant IMPORTED_AT = Instant.parse("2026-10-11T12:00:00Z");

    static boolean dockerAvailable() {
        return SharedPostgis.dockerAvailable();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        SharedPostgis.registerDatasource(registry);
    }

    @Autowired
    private UserRowRepository users;
    @Autowired
    private DriverRowRepository drivers;
    @Autowired
    private VehicleRowRepository vehicles;
    @Autowired
    private PassengerRowRepository passengers;
    @Autowired
    private JdbcTemplate jdbc;

    private ProfileImportService service;

    @BeforeEach
    void setUp() {
        service = new ProfileImportService(users, drivers, vehicles, passengers);
    }

    private static Map<String, Object> doc(Object... keyValues) {
        Map<String, Object> data = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            data.put((String) keyValues[i], keyValues[i + 1]);
        }
        return data;
    }

    private UserRow user(String firebaseUid) {
        return users.findByFirebaseUid(firebaseUid).orElseThrow();
    }

    @Test
    void copia_identidad_perfiles_y_vehiculo_y_enlaza_todo() {
        var driverDocs = Map.of(
                "d1", doc("firstName", "Ana", "email", "ana@test.com", "role", "admin",
                        "approvalStatus", "approved",
                        "approvedAt", Timestamp.ofTimeSecondsAndNanos(1_700_000_000L, 0),
                        "vehicleId", "v1", "language", "es", "fcmToken", "tok"),
                "d2", doc("firstName", "Luis"));          // documento viejo, casi vacío
        var vehicleDocs = Map.of("v1", doc("driverId", "d1", "plate", "PBX-1234", "year", 2020L));
        var passengerDocs = Map.of("p1", doc("firstName", "Eva", "email", "eva@test.com"));

        ProfileImportService.Report report =
                service.importProfiles(driverDocs, vehicleDocs, passengerDocs, IMPORTED_AT);

        assertThat(report.users()).isEqualTo(3);
        assertThat(report.drivers()).isEqualTo(2);
        assertThat(report.vehicles()).isEqualTo(1);
        assertThat(report.vehiclesSkipped()).isZero();
        assertThat(report.driversLinkedToVehicle()).isEqualTo(1);
        assertThat(report.passengers()).isEqualTo(1);
        assertThat(report.warnings()).isEmpty();

        UserRow ana = user("d1");
        assertThat(ana.getRoleCode()).isEqualTo("admin");
        assertThat(ana.getEmail()).isEqualTo("ana@test.com");
        DriverRow anaDriver = drivers.findById(ana.getId()).orElseThrow();
        assertThat(anaDriver.getApprovedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
        assertThat(anaDriver.getVehicleId()).isEqualTo("v1");
        assertThat(anaDriver.getFcmToken()).isEqualTo("tok");
        assertThat(vehicles.findById("v1").orElseThrow().getDriverId()).isEqualTo(ana.getId());

        assertThat(drivers.findById(user("d2").getId()).orElseThrow().getApprovalStatus())
                .as("anterior al sistema de aprobación").isEqualTo("approved");
        assertThat(user("p1").getRoleCode()).isEqualTo("user");
        assertThat(passengers.findById(user("p1").getId())).isPresent();
    }

    @Test
    void una_persona_que_es_conductor_y_pasajero_es_una_sola_fila_en_users() {
        var driverDocs = Map.of("misma", doc("firstName", "Como Conductor", "fcmToken", "tok-driver"));
        var passengerDocs = Map.of("misma", doc("firstName", "Como Pasajero", "fcmToken", "tok-passenger"));

        ProfileImportService.Report report =
                service.importProfiles(driverDocs, Map.of(), passengerDocs, IMPORTED_AT);

        assertThat(report.users()).isEqualTo(1);
        assertThat(users.count()).isEqualTo(1);
        UserRow persona = user("misma");
        assertThat(persona.getFirstName()).as("gana el del conductor").isEqualTo("Como Conductor");
        // Cada app conserva SU token: el push al pasajero no puede ir a la app del conductor.
        assertThat(drivers.findById(persona.getId()).orElseThrow().getFcmToken()).isEqualTo("tok-driver");
        assertThat(passengers.findById(persona.getId()).orElseThrow().getFcmToken()).isEqualTo("tok-passenger");
    }

    @Test
    void quien_aprobo_rechazo_o_bloqueo_se_traduce_de_uid_de_firebase_a_users_id() {
        var driverDocs = Map.of(
                "founder", doc("firstName", "Fundador", "role", "superuser"),
                "d1", doc("firstName", "Ana", "approvalStatus", "approved", "approvedBy", "founder",
                        "isBlocked", true, "blockReason", "Queja", "blockedBy", "founder"),
                "d2", doc("firstName", "Luis", "approvalStatus", "rejected", "rejectedBy", "founder",
                        "rejectionReason", "Sin documentos"),
                "d3", doc("firstName", "Eva", "approvalStatus", "approved", "approvedBy", "ya-no-existe"));

        service.importProfiles(driverDocs, Map.of(), Map.of(), IMPORTED_AT);

        var founderId = user("founder").getId();
        assertThat(drivers.findById(user("d1").getId()).orElseThrow().getApprovedBy()).isEqualTo(founderId);
        assertThat(user("d1").getBlockedBy()).isEqualTo(founderId);
        assertThat(drivers.findById(user("d2").getId()).orElseThrow().getRejectedBy()).isEqualTo(founderId);
        assertThat(drivers.findById(user("d3").getId()).orElseThrow().getApprovedBy())
                .as("un admin que ya no existe queda en NULL").isNull();
    }

    @Test
    void un_vehiculo_huerfano_se_omite_y_se_avisa_sin_romper_la_copia() {
        var driverDocs = Map.of("d1", doc("firstName", "Ana", "vehicleId", "v-fantasma"));
        var vehicleDocs = Map.of(
                "v-sin-dueno", doc("plate", "AAA-1"),
                "v-dueno-inexistente", doc("driverId", "no-existe"));

        ProfileImportService.Report report =
                service.importProfiles(driverDocs, vehicleDocs, Map.of(), IMPORTED_AT);

        assertThat(report.drivers()).isEqualTo(1);
        assertThat(report.vehicles()).isZero();
        assertThat(report.vehiclesSkipped()).isEqualTo(2);
        assertThat(report.driversLinkedToVehicle()).isZero();
        assertThat(report.warnings()).hasSize(3);
        assertThat(drivers.findById(user("d1").getId()).orElseThrow().getVehicleId()).isNull();
        assertThat(vehicles.count()).isZero();
    }

    @Test
    void es_idempotente_conserva_el_id_y_actualiza_lo_que_cambio() {
        var driverDocs = Map.of("d1", doc("firstName", "Ana", "vehicleId", "v1"));
        var vehicleDocs = Map.of("v1", doc("driverId", "d1", "plate", "PBX-1234"));
        service.importProfiles(driverDocs, vehicleDocs, Map.of(), IMPORTED_AT);
        var idAntes = user("d1").getId();

        var changedDrivers = Map.of("d1", doc("firstName", "Ana María", "vehicleId", "v1"));
        var changedVehicles = Map.of("v1", doc("driverId", "d1", "plate", "ABC-9999"));
        ProfileImportService.Report second =
                service.importProfiles(changedDrivers, changedVehicles, Map.of(), IMPORTED_AT);

        assertThat(second.driversLinkedToVehicle()).isEqualTo(1);
        assertThat(users.count()).as("no duplica").isEqualTo(1);
        assertThat(drivers.count()).isEqualTo(1);
        assertThat(vehicles.count()).isEqualTo(1);
        assertThat(user("d1").getId()).as("conserva su id").isEqualTo(idAntes);
        assertThat(user("d1").getFirstName()).isEqualTo("Ana María");
        assertThat(drivers.findById(idAntes).orElseThrow().getVehicleId()).isEqualTo("v1");
        assertThat(vehicles.findById("v1").orElseThrow().getPlate()).isEqualTo("ABC-9999");
    }

    // ---- Restricciones del esquema: la última defensa ----

    @Test
    void la_base_rechaza_un_rol_que_no_esta_en_la_tabla_roles() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (firebase_uid, role_code) VALUES ('x', 'dios')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void la_base_rechaza_un_estado_de_aprobacion_inventado() {
        jdbc.update("INSERT INTO users (firebase_uid) VALUES ('x')");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO drivers (user_id, approval_status) "
                        + "SELECT id, 'quizas' FROM users WHERE firebase_uid = 'x'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void la_base_rechaza_un_texto_mas_largo_que_su_limite() {
        String demasiadoLargo = "a".repeat(101);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (firebase_uid, first_name) VALUES ('x', ?)", demasiadoLargo))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void la_base_rechaza_dos_usuarios_con_el_mismo_uid_de_firebase() {
        jdbc.update("INSERT INTO users (firebase_uid) VALUES ('repetido')");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO users (firebase_uid) VALUES ('repetido')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void borrar_un_usuario_borra_su_perfil_de_conductor_y_sus_vehiculos() {
        service.importProfiles(
                Map.of("d1", doc("firstName", "Ana")),
                Map.of("v1", doc("driverId", "d1")),
                Map.of("d1", doc("language", "es")), IMPORTED_AT);

        jdbc.update("DELETE FROM users WHERE firebase_uid = 'd1'");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM drivers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM passengers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicles", Integer.class)).isZero();
    }

    @Test
    void la_tabla_roles_trae_los_tres_roles() {
        assertThat(jdbc.queryForList("SELECT code FROM roles ORDER BY code", String.class))
                .containsExactly("admin", "superuser", "user");
    }
}
