package com.areascript.taxiapp.user.infra;

import com.areascript.taxiapp.database.support.SharedPostgis;
import com.areascript.taxiapp.dispatch.domain.NearbyDriver;
import com.areascript.taxiapp.dispatch.domain.PresenceState;
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

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// La presencia y la búsqueda por cercanía contra un PostGIS REAL: es la prueba
// de que las consultas espaciales, el orden (longitud, latitud) y el filtro de
// frescura hacen lo que dicen. Cada test corre en una transacción que se deshace.
//
// Geografía de los datos: el origen es la Plaza (Riobamba, ~ -1.6635, -78.6546).
// A 0,001° de latitud hay ~111 m, y a 0,01° ~1,1 km.
//
// Se omite si Docker no está disponible.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresDriverPresence.class)
@EnabledIf("dockerAvailable")
class PostgresDriverPresenceTest {

    private static final double LAT = -1.6635;
    private static final double LNG = -78.6546;

    static boolean dockerAvailable() {
        return SharedPostgis.dockerAvailable();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        SharedPostgis.registerDatasource(registry);
    }

    @Autowired
    private PostgresDriverPresence presence;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void noDrivers() {
        // La base se comparte con otros tests; cada uno parte sin conductores.
        jdbc.update("DELETE FROM users");
    }

    private UUID driver(String uid) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, firebase_uid) VALUES (?, ?)", id, uid);
        jdbc.update("INSERT INTO drivers (user_id) VALUES (?)", id);
        return id;
    }

    // Un conductor en línea, libre y con ubicación, `northDegrees` al norte del origen.
    private void availableAt(String uid, double northDegrees) {
        driver(uid);
        presence.setOnline(uid, true);
        presence.recordLocationIfAvailable(uid, LAT + northDegrees, LNG);
    }

    private List<String> near(int radiusMeters, Set<String> exclude) {
        return presence.findAvailableNear(LAT, LNG, radiusMeters, Duration.ofSeconds(45), 10, exclude)
                .stream().map(NearbyDriver::driverUid).toList();
    }

    // ---- Interruptor y estado ----

    @Test
    void un_conductor_nuevo_esta_fuera_de_linea_y_libre() {
        driver("d1");

        assertThat(presence.state("d1")).contains(new PresenceState(false, false));
    }

    @Test
    void quien_no_tiene_perfil_de_conductor_no_tiene_estado() {
        jdbc.update("INSERT INTO users (firebase_uid) VALUES ('solo-usuario')");

        assertThat(presence.state("solo-usuario")).isEmpty();
        assertThat(presence.state("nadie")).isEmpty();
        assertThat(presence.setOnline("solo-usuario", true)).isFalse();
    }

    @Test
    void ponerse_en_linea_y_fuera_de_linea() {
        driver("d1");

        assertThat(presence.setOnline("d1", true)).isTrue();
        assertThat(presence.state("d1")).contains(new PresenceState(true, false));

        presence.setOnRide("d1", true);
        assertThat(presence.state("d1")).contains(new PresenceState(true, true));
    }

    // ---- Guardar la ubicación ----

    @Test
    void guarda_la_ubicacion_solo_si_esta_en_linea_y_libre() {
        driver("d1");

        assertThat(presence.recordLocationIfAvailable("d1", LAT, LNG)).as("fuera de línea").isFalse();

        presence.setOnline("d1", true);
        assertThat(presence.recordLocationIfAvailable("d1", LAT, LNG)).isTrue();

        presence.setOnRide("d1", true);
        assertThat(presence.recordLocationIfAvailable("d1", LAT, LNG)).as("en carrera").isFalse();
    }

    @Test
    void la_segunda_ubicacion_actualiza_la_fila_y_no_agrega_otra() {
        driver("d1");
        presence.setOnline("d1", true);

        presence.recordLocationIfAvailable("d1", LAT, LNG);
        presence.recordLocationIfAvailable("d1", LAT + 0.01, LNG);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM driver_locations", Integer.class)).isEqualTo(1);
    }

    @Test
    void la_ubicacion_se_guarda_con_longitud_y_latitud_en_su_orden() {
        driver("d1");
        presence.setOnline("d1", true);
        presence.recordLocationIfAvailable("d1", LAT, LNG);

        Double storedLat = jdbc.queryForObject(
                "SELECT ST_Y(location::geometry) FROM driver_locations", Double.class);
        Double storedLng = jdbc.queryForObject(
                "SELECT ST_X(location::geometry) FROM driver_locations", Double.class);

        assertThat(storedLat).isEqualTo(LAT);
        assertThat(storedLng).isEqualTo(LNG);
    }

    @Test
    void desconectarse_borra_la_ubicacion() {
        availableAt("d1", 0);

        presence.setOnline("d1", false);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM driver_locations", Integer.class)).isZero();
    }

    // ---- Búsqueda por cercanía ----

    @Test
    void encuentra_solo_a_los_que_estan_dentro_del_radio_del_mas_cercano_al_mas_lejano() {
        availableAt("a-500m", 0.0045);     // ~500 m
        availableAt("b-2km", 0.018);       // ~2 km
        availableAt("c-5km", 0.045);       // ~5 km

        assertThat(near(3000, Set.of())).containsExactly("a-500m", "b-2km");
        assertThat(near(6000, Set.of())).containsExactly("a-500m", "b-2km", "c-5km");
        assertThat(near(300, Set.of())).isEmpty();
    }

    @Test
    void devuelve_la_distancia_en_metros() {
        availableAt("d1", 0.0090);         // ~1 km al norte

        List<NearbyDriver> found = presence.findAvailableNear(LAT, LNG, 3000, Duration.ofSeconds(45), 10, Set.of());

        assertThat(found).hasSize(1);
        assertThat(found.get(0).distanceMeters()).isBetween(950.0, 1050.0);
    }

    @Test
    void no_ofrece_a_quien_esta_fuera_de_linea_ni_a_quien_esta_en_carrera() {
        availableAt("libre", 0.0045);
        availableAt("desconectado", 0.0045);
        availableAt("ocupado", 0.0045);
        // Se desconecta (esto borra su ubicación) y se vuelve a conectar sin reportar.
        presence.setOnline("desconectado", false);
        presence.setOnRide("ocupado", true);

        assertThat(near(3000, Set.of())).containsExactly("libre");
    }

    // Lo que hace que el conductor que cerró la app deje de recibir pedidos.
    @Test
    void no_ofrece_a_quien_dejo_de_reportar_su_ubicacion() {
        availableAt("reciente", 0.0045);
        availableAt("viejo", 0.0045);
        jdbc.update("UPDATE driver_locations SET updated_at = now() - interval '5 minutes' "
                + "WHERE driver_id = (SELECT d.user_id FROM drivers d JOIN users u ON u.id = d.user_id "
                + "WHERE u.firebase_uid = 'viejo')");

        assertThat(near(3000, Set.of())).containsExactly("reciente");
    }

    // El segundo anillo no repite a los del primero.
    @Test
    void excluye_a_los_que_ya_recibieron_la_oferta() {
        availableAt("a", 0.0045);
        availableAt("b", 0.018);

        assertThat(near(6000, Set.of("a"))).containsExactly("b");
        assertThat(near(6000, Set.of("a", "b"))).isEmpty();
    }

    @Test
    void respeta_el_maximo_de_conductores_del_anillo() {
        availableAt("a", 0.0010);
        availableAt("b", 0.0020);
        availableAt("c", 0.0030);

        List<NearbyDriver> found = presence.findAvailableNear(LAT, LNG, 3000, Duration.ofSeconds(45), 2, Set.of());

        assertThat(found).extracting(NearbyDriver::driverUid).containsExactly("a", "b");
    }

    @Test
    void la_busqueda_usa_el_indice_espacial() {
        Integer indexes = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_driver_locations_location' "
                        + "AND indexdef ILIKE '%gist%'", Integer.class);

        assertThat(indexes).isEqualTo(1);
    }
}
