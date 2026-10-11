package com.areascript.taxiapp.user.infra;

import com.areascript.taxiapp.database.support.SharedPostgis;
import com.areascript.taxiapp.user.domain.AppProfile;
import com.areascript.taxiapp.user.domain.DriverProfile;
import com.areascript.taxiapp.user.domain.UserAccount;
import com.areascript.taxiapp.user.domain.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Los adaptadores de Postgres de las personas y sus perfiles, contra una base
// REAL con las migraciones aplicadas. Cada test corre en una transacción que se
// deshace.
//
// Se omite si Docker no está disponible.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresUserAccounts.class, PostgresUserProfiles.class})
@EnabledIf("dockerAvailable")
class PostgresUserAdaptersTest {

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
    private JdbcTemplate jdbc;
    // El adaptador modifica entidades dentro de una transacción; en producción
    // el commit las escribe. Acá la transacción no termina, así que se vacía a
    // mano antes de mirar la base con JDBC.
    @Autowired
    private EntityManager entityManager;

    // ---- Personas ----

    @Test
    void la_primera_vez_crea_a_la_persona_con_rol_user() {
        UserAccount account = accounts.findOrProvision("uid-1", "ana@test.com", "Ana Pérez");

        assertThat(account.firebaseUid()).isEqualTo("uid-1");
        assertThat(account.email()).isEqualTo("ana@test.com");
        assertThat(account.displayName()).isEqualTo("Ana Pérez");
        assertThat(account.role()).isEqualTo(UserRole.USER);
        assertThat(account.blocked()).isFalse();
        assertThat(account.id()).isNotNull();
    }

    @Test
    void la_segunda_vez_devuelve_a_la_misma_persona_sin_sobrescribirla() {
        UserAccount first = accounts.findOrProvision("uid-1", "ana@test.com", "Ana");

        UserAccount second = accounts.findOrProvision("uid-1", "otro@test.com", "Otro Nombre");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.email()).as("los datos solo se usan al crear").isEqualTo("ana@test.com");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void datos_de_contacto_demasiado_largos_se_recortan_en_vez_de_romper_el_request() {
        String largo = "x".repeat(500);

        UserAccount account = accounts.findOrProvision("uid-largo", largo + "@test.com", largo);

        assertThat(account.email()).hasSize(254);
        assertThat(account.displayName()).hasSize(100);
    }

    @Test
    void cambiar_el_rol_se_refleja_en_la_siguiente_consulta() {
        accounts.findOrProvision("uid-1", "ana@test.com", "Ana");

        accounts.setRole("uid-1", UserRole.ADMIN);

        assertThat(accounts.findOrProvision("uid-1", "", "").role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void cambiar_el_rol_de_alguien_que_todavia_no_existe_lo_crea() {
        accounts.setRole("uid-nuevo", UserRole.SUPERUSER);

        assertThat(accounts.findOrProvision("uid-nuevo", "", "").role()).isEqualTo(UserRole.SUPERUSER);
    }

    @Test
    void borrar_a_la_persona_borra_sus_perfiles_y_vehiculos() {
        UserAccount account = accounts.findOrProvision("uid-1", "ana@test.com", "Ana");
        jdbc.update("INSERT INTO drivers (user_id) VALUES (?)", account.id());
        jdbc.update("INSERT INTO passengers (user_id) VALUES (?)", account.id());
        jdbc.update("INSERT INTO vehicles (vehicle_id, driver_id) VALUES ('v1', ?)", account.id());

        accounts.remove("uid-1");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drivers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM passengers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicles", Integer.class)).isZero();
    }

    @Test
    void borrar_a_alguien_que_no_existe_no_falla() {
        accounts.remove("nadie");
    }

    // ---- Perfiles ----

    @Test
    void lee_el_perfil_de_conductor_con_su_vehiculo() {
        UUID id = accounts.findOrProvision("uid-1", "ana@test.com", "Ana").id();
        jdbc.update("INSERT INTO drivers (user_id, phone_number, approval_status) VALUES (?, '0991112222', 'approved')", id);
        jdbc.update("INSERT INTO vehicles (vehicle_id, driver_id, plate, brand, year) VALUES ('v1', ?, 'PBX-1234', 'Toyota', 2020)", id);
        jdbc.update("UPDATE drivers SET vehicle_id = 'v1' WHERE user_id = ?", id);

        DriverProfile driver = profiles.findDriver(id).orElseThrow();

        assertThat(driver.phoneNumber()).isEqualTo("0991112222");
        assertThat(driver.approvalStatus()).isEqualTo("approved");
        assertThat(driver.vehicle().plate()).isEqualTo("PBX-1234");
        assertThat(driver.vehicle().year()).isEqualTo(2020);
    }

    @Test
    void una_persona_sin_perfil_no_tiene_perfil() {
        UUID id = accounts.findOrProvision("uid-1", "ana@test.com", "Ana").id();

        assertThat(profiles.findDriver(id)).isEmpty();
        assertThat(profiles.findPassenger(id)).isEmpty();
    }

    @Test
    void guarda_el_dispositivo_en_el_perfil_de_esa_app_y_no_en_el_de_la_otra() {
        UUID id = accounts.findOrProvision("uid-1", "ana@test.com", "Ana").id();
        jdbc.update("INSERT INTO drivers (user_id) VALUES (?)", id);
        jdbc.update("INSERT INTO passengers (user_id) VALUES (?)", id);

        assertThat(profiles.saveDevice(id, AppProfile.DRIVER, "tok-driver", "es")).isTrue();
        assertThat(profiles.saveDevice(id, AppProfile.PASSENGER, "tok-passenger", "en")).isTrue();
        entityManager.flush();

        assertThat(jdbc.queryForObject("SELECT fcm_token FROM drivers WHERE user_id = ?", String.class, id))
                .isEqualTo("tok-driver");
        assertThat(jdbc.queryForObject("SELECT language FROM drivers WHERE user_id = ?", String.class, id))
                .isEqualTo("es");
        assertThat(jdbc.queryForObject("SELECT fcm_token FROM passengers WHERE user_id = ?", String.class, id))
                .isEqualTo("tok-passenger");
        assertThat(jdbc.queryForObject("SELECT language FROM passengers WHERE user_id = ?", String.class, id))
                .isEqualTo("en");
    }

    @Test
    void un_campo_nulo_no_se_modifica() {
        UUID id = accounts.findOrProvision("uid-1", "ana@test.com", "Ana").id();
        jdbc.update("INSERT INTO drivers (user_id, fcm_token, language) VALUES (?, 'viejo', 'es')", id);

        profiles.saveDevice(id, AppProfile.DRIVER, null, "en");
        entityManager.flush();

        assertThat(jdbc.queryForObject("SELECT fcm_token FROM drivers WHERE user_id = ?", String.class, id))
                .as("el token no venía: se conserva").isEqualTo("viejo");
        assertThat(jdbc.queryForObject("SELECT language FROM drivers WHERE user_id = ?", String.class, id))
                .isEqualTo("en");
    }

    @Test
    void sin_perfil_en_esa_app_no_guarda_nada_ni_lo_crea() {
        UUID id = accounts.findOrProvision("uid-1", "ana@test.com", "Ana").id();

        assertThat(profiles.saveDevice(id, AppProfile.DRIVER, "tok", "es")).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM drivers", Integer.class)).isZero();
    }
}
