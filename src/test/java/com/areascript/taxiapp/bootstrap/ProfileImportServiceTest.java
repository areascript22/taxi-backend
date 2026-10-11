package com.areascript.taxiapp.bootstrap;

import com.areascript.taxiapp.driveradmin.infra.DriverRow;
import com.areascript.taxiapp.driveradmin.infra.DriverRowRepository;
import com.areascript.taxiapp.driveradmin.infra.VehicleRowRepository;
import com.areascript.taxiapp.passenger.infra.PassengerRowRepository;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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

    private static PostgreSQLContainer postgres;

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(
                    DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
            postgres.start();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

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
        service = new ProfileImportService(drivers, vehicles, passengers);
    }

    private static Map<String, Object> doc(Object... keyValues) {
        Map<String, Object> data = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            data.put((String) keyValues[i], keyValues[i + 1]);
        }
        return data;
    }

    @Test
    void copia_conductores_vehiculos_y_pasajeros_y_enlaza_el_vehiculo() {
        var driverDocs = Map.of(
                "d1", doc("firstName", "Ana", "role", "admin", "approvalStatus", "approved",
                        "approvedAt", Timestamp.ofTimeSecondsAndNanos(1_700_000_000L, 0),
                        "approvedBy", "founder", "vehicleId", "v1", "language", "es", "fcmToken", "tok"),
                "d2", doc("firstName", "Luis"));          // documento viejo, casi vacío
        var vehicleDocs = Map.of("v1", doc("driverId", "d1", "plate", "PBX-1234", "year", 2020L));
        var passengerDocs = Map.of("p1", doc("firstName", "Eva", "email", "eva@test.com"));

        ProfileImportService.Report report =
                service.importProfiles(driverDocs, vehicleDocs, passengerDocs, IMPORTED_AT);

        assertThat(report.drivers()).isEqualTo(2);
        assertThat(report.vehicles()).isEqualTo(1);
        assertThat(report.vehiclesSkipped()).isZero();
        assertThat(report.driversLinkedToVehicle()).isEqualTo(1);
        assertThat(report.passengers()).isEqualTo(1);
        assertThat(report.warnings()).isEmpty();

        assertThat(drivers.count()).isEqualTo(2);
        DriverRow d1 = drivers.findById("d1").orElseThrow();
        assertThat(d1.getRole()).isEqualTo("admin");
        assertThat(d1.getApprovedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
        assertThat(d1.getVehicleId()).isEqualTo("v1");
        assertThat(d1.getFcmToken()).isEqualTo("tok");
        assertThat(drivers.findById("d2").orElseThrow().getApprovalStatus())
                .as("anterior al sistema de aprobación").isEqualTo("approved");

        assertThat(vehicles.findById("v1").orElseThrow().getYear()).isEqualTo(2020);
        assertThat(passengers.findById("p1").orElseThrow().getEmail()).isEqualTo("eva@test.com");
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
        assertThat(drivers.findById("d1").orElseThrow().getVehicleId()).isNull();
        assertThat(vehicles.count()).isZero();
    }

    @Test
    void es_idempotente_y_la_segunda_corrida_actualiza_lo_que_cambio() {
        var driverDocs = Map.of("d1", doc("firstName", "Ana", "vehicleId", "v1"));
        var vehicleDocs = Map.of("v1", doc("driverId", "d1", "plate", "PBX-1234"));
        service.importProfiles(driverDocs, vehicleDocs, Map.of(), IMPORTED_AT);

        var changedDrivers = Map.of("d1", doc("firstName", "Ana María", "vehicleId", "v1"));
        var changedVehicles = Map.of("v1", doc("driverId", "d1", "plate", "ABC-9999"));
        ProfileImportService.Report second =
                service.importProfiles(changedDrivers, changedVehicles, Map.of(), IMPORTED_AT);

        assertThat(second.driversLinkedToVehicle()).isEqualTo(1);
        assertThat(drivers.count()).as("no duplica").isEqualTo(1);
        assertThat(vehicles.count()).isEqualTo(1);
        assertThat(drivers.findById("d1").orElseThrow().getFirstName()).isEqualTo("Ana María");
        assertThat(drivers.findById("d1").orElseThrow().getVehicleId()).isEqualTo("v1");
        assertThat(vehicles.findById("v1").orElseThrow().getPlate()).isEqualTo("ABC-9999");
    }

    // Las restricciones del esquema son la última defensa: aunque el código
    // tenga un error, la base se niega a guardar un rol o estado inventado.
    @Test
    void la_base_rechaza_un_rol_inventado() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO drivers (uid, role) VALUES ('x', 'dios')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void la_base_rechaza_un_estado_de_aprobacion_inventado() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO drivers (uid, approval_status) VALUES ('x', 'quizas')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void borrar_un_conductor_borra_sus_vehiculos() {
        service.importProfiles(
                Map.of("d1", doc("firstName", "Ana")),
                Map.of("v1", doc("driverId", "d1")),
                Map.of(), IMPORTED_AT);

        jdbc.update("DELETE FROM drivers WHERE uid = 'd1'");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicles", Integer.class)).isZero();
    }
}
