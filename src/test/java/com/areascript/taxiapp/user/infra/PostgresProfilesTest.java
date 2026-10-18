package com.areascript.taxiapp.user.infra;

import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.database.support.SharedPostgis;
import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.Driver;
import com.areascript.taxiapp.driveradmin.domain.DriverPage;
import com.areascript.taxiapp.driveradmin.domain.DriverRole;
import com.areascript.taxiapp.notification.domain.Device;
import com.areascript.taxiapp.notification.domain.Recipient;
import com.areascript.taxiapp.user.domain.AppProfile;
import com.areascript.taxiapp.user.domain.DriverProfileData;
import com.areascript.taxiapp.user.domain.PassengerProfileData;
import com.areascript.taxiapp.user.domain.UserAccount;
import com.areascript.taxiapp.user.domain.VehicleData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Perfiles de conductor y pasajero, el repositorio del panel de administración
// y el registro de dispositivos, contra una base REAL con las migraciones
// aplicadas. Cada test corre en una transacción que se deshace.
//
// Se omite si Docker no está disponible.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresUserAccounts.class, PostgresUserProfiles.class,
        PostgresDriverRepository.class, PostgresDeviceRegistry.class})
@EnabledIf("dockerAvailable")
class PostgresProfilesTest {

    static boolean dockerAvailable() {
        return SharedPostgis.dockerAvailable();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        SharedPostgis.registerDatasource(registry);
    }

    @Autowired
    private PostgresUserAccounts accounts;
    @Autowired
    private PostgresUserProfiles profiles;
    @Autowired
    private PostgresDriverRepository driverRepository;
    @Autowired
    private PostgresDeviceRegistry deviceRegistry;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanBase() {
        jdbc.update("DELETE FROM users");
    }

    private static VehicleData vehicle(String plate, String color) {
        return new VehicleData(plate, "Toyota", "Corolla", 2020, color, "REG-1");
    }

    private static DriverProfileData driverData(String plate, String color) {
        return new DriverProfileData("Ana", "Pérez", "0991112222", null, vehicle(plate, color));
    }

    private UserAccount person(String uid) {
        return accounts.findOrProvision(uid, uid + "@test.com", "Nombre " + uid);
    }

    // ---- Alta y edición de conductor ----

    @Test
    void el_alta_crea_el_perfil_pendiente_con_su_vehiculo_enlazado() {
        UserAccount ana = person("d1");

        profiles.saveDriverProfile(ana.id(), "d1@test.com", driverData("PBX-1234", "Amarillo"));

        Driver driver = driverRepository.findById("d1").orElseThrow();
        assertThat(driver.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(driver.firstName()).isEqualTo("Ana");
        assertThat(driver.lastName()).isEqualTo("Pérez");
        assertThat(driver.phoneNumber()).isEqualTo("0991112222");
        assertThat(driver.blocked()).isFalse();
        assertThat(driver.role()).isEqualTo(DriverRole.DRIVER);
        assertThat(driver.vehicle()).isNotNull();
        assertThat(driver.vehicle().plate()).isEqualTo("PBX-1234");
        assertThat(driver.vehicle().driverId()).isEqualTo("d1");
        assertThat(driver.vehicle().verificationStatus()).isEqualTo("pending");
    }

    @Test
    void editar_conserva_la_aprobacion_y_actualiza_el_mismo_vehiculo() {
        UserAccount ana = person("d1");
        profiles.saveDriverProfile(ana.id(), "d1@test.com", driverData("PBX-1234", "Amarillo"));
        driverRepository.applyApproval("d1", ApprovalStatus.APPROVED, null, "admin-1");
        String vehicleId = driverRepository.findById("d1").orElseThrow().vehicle().vehicleId();

        profiles.saveDriverProfile(ana.id(), "d1@test.com", driverData("PBX-1234", "Rojo"));

        Driver driver = driverRepository.findById("d1").orElseThrow();
        assertThat(driver.approvalStatus()).as("editar no aprueba ni desaprueba").isEqualTo(ApprovalStatus.APPROVED);
        assertThat(driver.vehicle().vehicleId()).as("el mismo vehículo, no uno nuevo").isEqualTo(vehicleId);
        assertThat(driver.vehicle().color()).isEqualTo("Rojo");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicles", Integer.class)).isEqualTo(1);
    }

    @Test
    void cambiar_la_placa_vuelve_la_verificacion_del_vehiculo_a_pendiente() {
        UserAccount ana = person("d1");
        profiles.saveDriverProfile(ana.id(), "d1@test.com", driverData("PBX-1234", "Amarillo"));
        jdbc.update("UPDATE vehicles SET verification_status = 'verified'");
        entityManager.clear();

        profiles.saveDriverProfile(ana.id(), "d1@test.com", driverData("ABC-9999", "Amarillo"));

        assertThat(driverRepository.findById("d1").orElseThrow().vehicle().verificationStatus())
                .isEqualTo("pending");
    }

    @Test
    void cambiar_solo_el_color_no_reinicia_la_verificacion() {
        UserAccount ana = person("d1");
        profiles.saveDriverProfile(ana.id(), "d1@test.com", driverData("PBX-1234", "Amarillo"));
        jdbc.update("UPDATE vehicles SET verification_status = 'verified'");
        entityManager.clear();

        profiles.saveDriverProfile(ana.id(), "d1@test.com", driverData("PBX-1234", "Rojo"));

        assertThat(driverRepository.findById("d1").orElseThrow().vehicle().verificationStatus())
                .isEqualTo("verified");
    }

    @Test
    void una_persona_sin_perfil_de_conductor_no_aparece_como_conductor() {
        person("solo-usuario");

        assertThat(driverRepository.findById("solo-usuario")).isEmpty();
        assertThat(driverRepository.findAll().drivers()).isEmpty();
    }

    // ---- Pasajero ----

    @Test
    void el_perfil_de_pasajero_nace_activo_y_comparte_el_nombre_con_la_persona() {
        UserAccount luis = person("p1");

        profiles.savePassengerProfile(luis.id(), "p1@test.com", new PassengerProfileData("Luis", "Mora", null));

        assertThat(profiles.findPassenger(luis.id()).orElseThrow().status()).isEqualTo("active");
        assertThat(accounts.findOrProvision("p1", "", "").displayName()).isEqualTo("Luis Mora");
    }

    @Test
    void una_misma_persona_puede_tener_los_dos_perfiles() {
        UserAccount ana = person("dual");

        profiles.saveDriverProfile(ana.id(), "d@test.com", driverData("PBX-1234", "Amarillo"));
        profiles.savePassengerProfile(ana.id(), "d@test.com", new PassengerProfileData("Ana", "Pérez", null));

        assertThat(profiles.findDriver(ana.id())).isPresent();
        assertThat(profiles.findPassenger(ana.id())).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    // ---- Panel de administración ----

    @Test
    void aprobar_registra_quien_y_cuando_y_limpia_un_rechazo_anterior() {
        UserAccount admin = person("admin-1");
        UserAccount ana = person("d1");
        profiles.saveDriverProfile(ana.id(), "d1@test.com", driverData("PBX-1234", "Amarillo"));
        driverRepository.applyApproval("d1", ApprovalStatus.REJECTED, "Documentos ilegibles", "admin-1");
        assertThat(driverRepository.findById("d1").orElseThrow().rejectionReason()).isEqualTo("Documentos ilegibles");

        driverRepository.applyApproval("d1", ApprovalStatus.APPROVED, null, "admin-1");

        Driver driver = driverRepository.findById("d1").orElseThrow();
        assertThat(driver.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(driver.rejectionReason()).as("aprobar limpia el rechazo").isNull();
        assertThat(jdbc.queryForObject(
                "SELECT approved_by FROM drivers WHERE user_id = ?", java.util.UUID.class, ana.id()))
                .isEqualTo(admin.id());
    }

    @Test
    void bloquear_y_desbloquear_son_de_la_cuenta_y_el_desbloqueo_no_deja_rastro() {
        UserAccount admin = person("admin-1");
        UserAccount ana = person("d1");
        profiles.saveDriverProfile(ana.id(), "d1@test.com", driverData("PBX-1234", "Amarillo"));

        driverRepository.applyBlock("d1", true, "Queja de un pasajero", "admin-1");

        Driver blocked = driverRepository.findById("d1").orElseThrow();
        assertThat(blocked.blocked()).isTrue();
        assertThat(blocked.blockReason()).isEqualTo("Queja de un pasajero");
        assertThat(blocked.blockedBy()).as("el panel ve el uid de Firebase de quien bloqueó").isEqualTo("admin-1");
        assertThat(blocked.blockedAt()).isNotNull();
        assertThat(accounts.findOrProvision("d1", "", "").blocked()).isTrue();

        driverRepository.applyBlock("d1", false, null, "admin-1");

        Driver unblocked = driverRepository.findById("d1").orElseThrow();
        assertThat(unblocked.blocked()).isFalse();
        assertThat(unblocked.blockReason()).isNull();
        assertThat(unblocked.blockedBy()).isNull();
        assertThat(admin.id()).isNotNull();
    }

    @Test
    void cambiar_el_rol_usa_el_vocabulario_del_panel_y_el_de_la_base() {
        UserAccount ana = person("d1");
        profiles.saveDriverProfile(ana.id(), "d1@test.com", driverData("PBX-1234", "Amarillo"));

        driverRepository.updateRole("d1", DriverRole.ADMIN);
        assertThat(driverRepository.findById("d1").orElseThrow().role()).isEqualTo(DriverRole.ADMIN);
        assertThat(jdbc.queryForObject(
                "SELECT role_code FROM users WHERE firebase_uid = 'd1'", String.class)).isEqualTo("admin");

        driverRepository.updateRole("d1", DriverRole.DRIVER);
        assertThat(jdbc.queryForObject(
                "SELECT role_code FROM users WHERE firebase_uid = 'd1'", String.class)).isEqualTo("user");
    }

    @Test
    void cambiar_el_rol_de_quien_no_existe_es_un_404() {
        assertThatThrownBy(() -> driverRepository.updateRole("nadie", DriverRole.ADMIN))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void asegurar_el_rol_crea_a_la_persona_si_no_existe() {
        driverRepository.ensureRole("fundador", DriverRole.SUPERUSER);

        assertThat(jdbc.queryForObject(
                "SELECT role_code FROM users WHERE firebase_uid = 'fundador'", String.class)).isEqualTo("superuser");
    }

    @Test
    void la_paginacion_por_cursor_recorre_a_todos_sin_repetir() {
        for (String uid : new String[]{"d1", "d2", "d3", "d4", "d5"}) {
            profiles.saveDriverProfile(person(uid).id(), uid + "@test.com", driverData("PBX-" + uid, "Amarillo"));
        }

        DriverPage first = driverRepository.findPage(2, null);
        DriverPage second = driverRepository.findPage(2, first.nextCursor());
        DriverPage third = driverRepository.findPage(2, second.nextCursor());

        assertThat(first.drivers()).extracting(Driver::uid).containsExactly("d1", "d2");
        assertThat(first.hasMore()).isTrue();
        assertThat(second.drivers()).extracting(Driver::uid).containsExactly("d3", "d4");
        assertThat(second.hasMore()).isTrue();
        assertThat(third.drivers()).extracting(Driver::uid).containsExactly("d5");
        assertThat(third.hasMore()).isFalse();
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void listar_todos_trae_vehiculo_y_bloqueo_sin_una_consulta_por_conductor() {
        for (String uid : new String[]{"d1", "d2"}) {
            profiles.saveDriverProfile(person(uid).id(), uid + "@test.com", driverData("PBX-" + uid, "Amarillo"));
        }
        driverRepository.applyBlock("d2", true, "Motivo", "d1");

        DriverPage all = driverRepository.findAll();

        assertThat(all.drivers()).hasSize(2);
        assertThat(all.drivers()).allSatisfy(driver -> assertThat(driver.vehicle()).isNotNull());
        assertThat(all.drivers().get(1).blocked()).isTrue();
    }

    // ---- Dispositivo para las notificaciones ----

    @Test
    void el_registro_de_dispositivo_lee_el_token_del_perfil_de_cada_app() {
        UserAccount ana = person("dual");
        profiles.saveDriverProfile(ana.id(), "d@test.com", driverData("PBX-1234", "Amarillo"));
        profiles.savePassengerProfile(ana.id(), "d@test.com", new PassengerProfileData("Ana", "Pérez", null));
        profiles.saveDevice(ana.id(), AppProfile.DRIVER, "tok-driver", "es");
        profiles.saveDevice(ana.id(), AppProfile.PASSENGER, "tok-passenger", "en");

        Optional<Device> asDriver = deviceRegistry.findDevice(Recipient.driver("dual"));
        Optional<Device> asPassenger = deviceRegistry.findDevice(Recipient.passenger("dual"));

        assertThat(asDriver).contains(new Device("tok-driver", "es"));
        assertThat(asPassenger).contains(new Device("tok-passenger", "en"));
    }

    @Test
    void sin_perfil_en_esa_app_no_hay_dispositivo() {
        UserAccount ana = person("solo-conductor");
        profiles.saveDriverProfile(ana.id(), "d@test.com", driverData("PBX-1234", "Amarillo"));

        assertThat(deviceRegistry.findDevice(Recipient.passenger("solo-conductor"))).isEmpty();
        assertThat(deviceRegistry.findDevice(Recipient.driver("nadie"))).isEmpty();
    }
}
