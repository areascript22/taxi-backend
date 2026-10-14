package com.areascript.taxiapp.dispatch.infra;

import com.areascript.taxiapp.database.support.SharedPostgis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// La configuración del despacho contra un Postgres REAL, con las migraciones
// aplicadas y Hibernate validando las entidades contra ellas. Cada test corre en
// una transacción que se deshace, así que se puede modificar libremente.
//
// Se omite si Docker no está disponible.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIf("dockerAvailable")
class DispatchSettingsTest {

    static boolean dockerAvailable() {
        return SharedPostgis.dockerAvailable();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        SharedPostgis.registerDatasource(registry);
    }

    @Autowired
    private DispatchSettingsRowRepository settings;
    @Autowired
    private DispatchRingRowRepository rings;
    @Autowired
    private JdbcTemplate jdbc;

    // ---- Valores iniciales ----

    @Test
    void trae_tres_anillos_de_3_6_y_9_km_en_orden() {
        List<DispatchRingRow> all = rings.findAllByOrderByRingNumberAsc();

        assertThat(all).extracting(DispatchRingRow::getRingNumber).containsExactly(1, 2, 3);
        assertThat(all).extracting(DispatchRingRow::getRadiusMeters).containsExactly(3000, 6000, 9000);
        assertThat(all).extracting(DispatchRingRow::getWaitSeconds).containsOnly(10);
        assertThat(all).extracting(DispatchRingRow::getMaxDrivers).containsOnly(10);
    }

    @Test
    void trae_una_unica_fila_de_ajustes_con_los_valores_iniciales() {
        List<DispatchSettingsRow> all = settings.findAll();

        assertThat(all).hasSize(1);
        DispatchSettingsRow row = all.get(0);
        assertThat(row.getId()).isEqualTo(1);
        assertThat(row.getLocationMaxAgeSeconds()).isEqualTo(45);
        assertThat(row.getDriverPingIntervalSeconds()).isEqualTo(10);
        assertThat(row.getDriverPingMinMoveMeters()).isEqualTo(30);
        assertThat(row.getUpdatedBy()).as("valores iniciales: nadie los cambió").isNull();
    }

    // ---- Lo que un administrador puede cambiar ----

    @Test
    void un_administrador_puede_cambiar_un_anillo_y_quedar_registrado() {
        UUID admin = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, firebase_uid, role_code) VALUES (?, 'admin-1', 'admin')", admin);

        DispatchRingRow ring = rings.findById(1).orElseThrow();
        ring.setRadiusMeters(2000);
        ring.setWaitSeconds(15);
        ring.setUpdatedBy(admin);
        rings.saveAndFlush(ring);

        DispatchRingRow reloaded = rings.findAllByOrderByRingNumberAsc().get(0);
        assertThat(reloaded.getRadiusMeters()).isEqualTo(2000);
        assertThat(reloaded.getWaitSeconds()).isEqualTo(15);
        assertThat(reloaded.getUpdatedBy()).isEqualTo(admin);
    }

    @Test
    void se_puede_agregar_un_cuarto_anillo() {
        rings.saveAndFlush(new DispatchRingRow(4, 12000, 10, 10));

        assertThat(rings.findAllByOrderByRingNumberAsc()).hasSize(4);
    }

    // ---- Restricciones del esquema: la última defensa ----

    @Test
    void la_base_rechaza_una_segunda_fila_de_ajustes() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dispatch_settings (id) VALUES (2)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Una sola sentencia que falla por test: en Postgres, tras el primer error la
    // transacción queda abortada y cualquier otra sentencia falla por eso, no
    // por la regla que se quiere probar.
    @Test
    void la_base_rechaza_un_radio_menor_a_100_metros() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO dispatch_rings (ring_number, radius_meters) VALUES (4, 50)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void la_base_rechaza_un_radio_mayor_a_50_km() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO dispatch_rings (ring_number, radius_meters) VALUES (4, 60000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void la_base_rechaza_una_espera_de_cero_segundos() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO dispatch_rings (ring_number, radius_meters, wait_seconds) VALUES (4, 12000, 0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void la_base_rechaza_cero_conductores_por_anillo() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO dispatch_rings (ring_number, radius_meters, max_drivers) VALUES (4, 12000, 0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void la_base_rechaza_dos_anillos_con_el_mismo_numero() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO dispatch_rings (ring_number, radius_meters) VALUES (1, 15000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void la_base_rechaza_dos_anillos_con_el_mismo_radio() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO dispatch_rings (ring_number, radius_meters) VALUES (4, 3000)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void la_base_rechaza_una_frescura_de_ubicacion_absurda() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE dispatch_settings SET location_max_age_seconds = 0"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void la_base_rechaza_registrar_como_autor_a_un_usuario_que_no_existe() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE dispatch_rings SET updated_by = ? WHERE ring_number = 1", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
