package com.areascript.taxiapp.dispatch.infra;

import com.areascript.taxiapp.database.support.SharedPostgis;
import com.areascript.taxiapp.dispatch.domain.DispatchPlan;
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

import static org.assertj.core.api.Assertions.assertThat;

// La configuración del despacho que lee el servidor, contra la base real: lo
// que un administrador deja en las tablas es lo que se despacha.
//
// Se omite si Docker no está disponible.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaDispatchPlans.class)
@EnabledIf("dockerAvailable")
class JpaDispatchPlansTest {

    static boolean dockerAvailable() {
        return SharedPostgis.dockerAvailable();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        SharedPostgis.registerDatasource(registry);
    }

    @Autowired
    private JpaDispatchPlans plans;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void lee_los_valores_iniciales_de_la_migracion() {
        DispatchPlan plan = plans.current();

        assertThat(plan.rings()).hasSize(3);
        assertThat(plan.rings().get(0).radiusMeters()).isEqualTo(3000);
        assertThat(plan.rings().get(2).radiusMeters()).isEqualTo(9000);
        assertThat(plan.rings().get(0).waitTime()).isEqualTo(Duration.ofSeconds(10));
        assertThat(plan.rings().get(0).maxDrivers()).isEqualTo(10);
        assertThat(plan.locationMaxAge()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void un_cambio_del_administrador_vale_en_la_siguiente_lectura() {
        jdbc.update("UPDATE dispatch_rings SET radius_meters = 2000, wait_seconds = 15 WHERE ring_number = 1");
        jdbc.update("UPDATE dispatch_settings SET location_max_age_seconds = 30");

        DispatchPlan plan = plans.current();

        assertThat(plan.rings().get(0).radiusMeters()).isEqualTo(2000);
        assertThat(plan.rings().get(0).waitTime()).isEqualTo(Duration.ofSeconds(15));
        assertThat(plan.locationMaxAge()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void agregar_un_cuarto_anillo_amplia_la_busqueda() {
        jdbc.update("INSERT INTO dispatch_rings (ring_number, radius_meters, wait_seconds, max_drivers) "
                + "VALUES (4, 12000, 10, 10)");

        assertThat(plans.current().rings()).hasSize(4);
    }

    // Lo que no puede impedir la base: radios que no crecen. Mejor no despachar
    // que despachar con una configuración incoherente.
    @Test
    void una_configuracion_incoherente_no_despacha() {
        jdbc.update("UPDATE dispatch_rings SET radius_meters = 2500 WHERE ring_number = 2");   // < anillo 1 (3000)

        assertThat(plans.current().isEmpty()).isTrue();
    }

    @Test
    void sin_anillos_el_plan_es_vacio() {
        jdbc.update("DELETE FROM dispatch_rings");

        assertThat(plans.current().isEmpty()).isTrue();
    }
}
