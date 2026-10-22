package com.areascript.taxiapp.dispatch.infra;

import com.areascript.taxiapp.common.error.ConflictException;
import com.areascript.taxiapp.database.support.SharedPostgis;
import com.areascript.taxiapp.dispatch.domain.DispatchConfig;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigChange;
import com.areascript.taxiapp.dispatch.domain.DispatchPlan;
import com.areascript.taxiapp.dispatch.domain.DispatchRing;
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

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// La edición de la configuración del despacho contra un Postgres REAL, con las
// migraciones aplicadas y Hibernate validando las entidades contra ellas. Cada
// test corre en una transacción que se deshace.
//
// Lo que hay que probar acá y no en memoria: el reemplazo de anillos respeta
// los CHECK y la unicidad del radio (que no es diferible), el historial guarda
// ambas fotos y la versión / el bloqueo de la fila hacen lo que dicen.
//
// Se omite si Docker no está disponible.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaDispatchConfigStore.class)
@EnabledIf("dockerAvailable")
class JpaDispatchConfigStoreTest {

    static boolean dockerAvailable() {
        return SharedPostgis.dockerAvailable();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        SharedPostgis.registerDatasource(registry);
    }

    @Autowired
    private JpaDispatchConfigStore store;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private static DispatchRing ring(int number, int radius, int waitSeconds, int maxDrivers) {
        return new DispatchRing(number, radius, Duration.ofSeconds(waitSeconds), maxDrivers);
    }

    private static DispatchPlan plan(int maxAgeSeconds, DispatchRing... rings) {
        return new DispatchPlan(List.of(rings), Duration.ofSeconds(maxAgeSeconds));
    }

    // Una persona real para el autor (changed_by y updated_by son claves foráneas).
    private UUID editor() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, firebase_uid, email, first_name, last_name) VALUES (?, ?, ?, ?, ?)",
                id, "uid-" + id, id + "@test.com", "Edi", "Tor");
        return id;
    }

    @Test
    void carga_los_valores_iniciales_con_el_interruptor_apagado_y_la_version_en_cero() {
        DispatchConfig config = store.load();

        assertThat(config.plan().rings()).hasSize(3);
        assertThat(config.adminsCanEdit()).isFalse();
        assertThat(config.version()).isZero();
        assertThat(config.updatedBy()).isNull();
    }

    @Test
    void reemplaza_los_anillos_sube_la_version_y_deja_al_autor() {
        UUID editor = editor();

        DispatchConfig saved = store.replace(
                plan(60, ring(1, 2000, 8, 5), ring(2, 5000, 12, 7)), 0, editor);

        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.updatedBy()).isEqualTo(editor);
        DispatchConfig reloaded = store.load();
        assertThat(reloaded.plan().rings()).containsExactly(ring(1, 2000, 8, 5), ring(2, 5000, 12, 7));
        assertThat(reloaded.plan().locationMaxAge()).isEqualTo(Duration.ofSeconds(60));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch_rings", Integer.class)).isEqualTo(2);
    }

    @Test
    void intercambiar_radios_no_choca_con_la_unicidad_del_radio() {
        // Con actualización fila por fila, el anillo 1 tomaría el radio del 2
        // mientras este todavía lo tiene: viola uq_dispatch_rings_radius.
        store.replace(plan(45, ring(1, 3000, 10, 10), ring(2, 6000, 10, 10)), 0, editor());

        DispatchConfig saved = store.replace(
                plan(45, ring(1, 6000, 10, 10), ring(2, 9000, 10, 10), ring(3, 12000, 10, 10)), 1, editor());

        assertThat(saved.plan().rings()).extracting(DispatchRing::radiusMeters).containsExactly(6000, 9000, 12000);
        assertThat(store.load().plan().rings()).extracting(DispatchRing::radiusMeters)
                .containsExactly(6000, 9000, 12000);
    }

    @Test
    void quitar_anillos_deja_solo_los_nuevos() {
        store.replace(plan(45, ring(1, 3000, 10, 10)), 0, editor());

        assertThat(store.load().plan().rings()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch_rings", Integer.class)).isEqualTo(1);
    }

    @Test
    void una_version_vieja_es_un_conflicto_y_no_cambia_nada() {
        store.replace(plan(45, ring(1, 2000, 10, 10)), 0, editor());

        assertThatThrownBy(() -> store.replace(plan(45, ring(1, 9000, 10, 10)), 0, editor()))
                .isInstanceOf(ConflictException.class);

        assertThat(store.load().plan().rings().get(0).radiusMeters()).isEqualTo(2000);
        assertThat(store.load().version()).isEqualTo(1);
    }

    @Test
    void guardar_exactamente_lo_vigente_no_sube_la_version_ni_deja_historial() {
        DispatchPlan current = store.load().plan();

        DispatchConfig saved = store.replace(current, 0, editor());

        assertThat(saved.version()).isZero();
        assertThat(store.history(10, null)).isEmpty();
    }

    @Test
    void el_interruptor_se_guarda_no_toca_la_version_y_deja_historial() {
        UUID editor = editor();

        DispatchConfig saved = store.setAdminsCanEdit(true, editor);

        assertThat(saved.adminsCanEdit()).isTrue();
        assertThat(saved.version()).isZero();
        assertThat(store.load().adminsCanEdit()).isTrue();
        List<DispatchConfigChange> history = store.history(10, null);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).type()).isEqualTo(DispatchConfigChange.Type.ADMINS_CAN_EDIT);
        assertThat(history.get(0).changedBy()).isEqualTo(editor);
        assertThat(history.get(0).before().adminsCanEdit()).isFalse();
        assertThat(history.get(0).after().adminsCanEdit()).isTrue();
    }

    @Test
    void poner_el_interruptor_en_el_valor_que_ya_tiene_no_deja_rastro() {
        store.setAdminsCanEdit(false, editor());

        assertThat(store.history(10, null)).isEmpty();
    }

    @Test
    void el_historial_guarda_la_foto_de_antes_y_de_despues_y_pagina() {
        UUID editor = editor();
        store.replace(plan(60, ring(1, 2000, 8, 5), ring(2, 5000, 12, 7)), 0, editor);
        store.replace(plan(70, ring(1, 2500, 9, 6)), 1, editor);

        List<DispatchConfigChange> history = store.history(10, null);

        assertThat(history).hasSize(2);
        DispatchConfigChange latest = history.get(0);
        assertThat(latest.type()).isEqualTo(DispatchConfigChange.Type.CONFIG);
        assertThat(latest.before().rings()).containsExactly(ring(1, 2000, 8, 5), ring(2, 5000, 12, 7));
        assertThat(latest.before().locationMaxAge()).isEqualTo(Duration.ofSeconds(60));
        assertThat(latest.after().rings()).containsExactly(ring(1, 2500, 9, 6));
        assertThat(latest.after().locationMaxAge()).isEqualTo(Duration.ofSeconds(70));
        DispatchConfigChange first = history.get(1);
        assertThat(first.before().rings()).hasSize(3);

        assertThat(store.history(1, null)).hasSize(1);
        assertThat(store.history(10, latest.id())).extracting(DispatchConfigChange::id).containsExactly(first.id());
    }

    @Test
    void borrar_a_quien_edito_no_borra_el_historial() {
        UUID editor = editor();
        store.replace(plan(45, ring(1, 2000, 10, 10)), 0, editor);

        jdbc.update("DELETE FROM users WHERE id = ?", editor);
        entityManager.clear();

        List<DispatchConfigChange> history = store.history(10, null);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).changedBy()).isNull();
        assertThat(store.load().updatedBy()).isNull();
    }

    @Test
    void lo_que_guarda_el_panel_es_lo_que_lee_el_despacho() {
        store.replace(plan(90, ring(1, 1500, 7, 3)), 0, editor());
        entityManager.clear();

        // JpaDispatchPlans es la ruta caliente del despacho (se lee en cada
        // búsqueda): tiene que ver lo que el panel acaba de guardar.
        DispatchPlan read = new JpaDispatchPlans(ringRows, settingsRows).current();

        assertThat(read.rings()).containsExactly(ring(1, 1500, 7, 3));
        assertThat(read.locationMaxAge()).isEqualTo(Duration.ofSeconds(90));
    }

    @Autowired
    private DispatchRingRowRepository ringRows;
    @Autowired
    private DispatchSettingsRowRepository settingsRows;
}
