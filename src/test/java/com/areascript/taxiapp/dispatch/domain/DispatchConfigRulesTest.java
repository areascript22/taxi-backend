package com.areascript.taxiapp.dispatch.domain;

import com.areascript.taxiapp.common.error.InvalidInputException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Lo que se valida ANTES de guardar la configuración del despacho. Un plan
// inválido guardado haría que el despacho se apague sin avisar, así que cada
// regla tiene que rechazar con un mensaje que el administrador pueda corregir.
class DispatchConfigRulesTest {

    private static final DispatchConfigLimits LIMITS =
            new DispatchConfigLimits(6, Duration.ofSeconds(120), Duration.ofSeconds(30));
    private static final Duration AGE = Duration.ofSeconds(45);

    private static DispatchRing ring(int number, int radius, int waitSeconds, int maxDrivers) {
        return new DispatchRing(number, radius, Duration.ofSeconds(waitSeconds), maxDrivers);
    }

    private static List<DispatchRing> valid() {
        return List.of(ring(1, 3000, 10, 10), ring(2, 6000, 10, 10), ring(3, 9000, 10, 10));
    }

    @Test
    void acepta_una_configuracion_valida() {
        DispatchPlan plan = DispatchConfigRules.buildPlan(valid(), AGE, LIMITS);

        assertThat(plan.rings()).hasSize(3);
        assertThat(plan.locationMaxAge()).isEqualTo(AGE);
    }

    @Test
    void rechaza_una_lista_vacia_porque_apagaria_el_despacho_en_silencio() {
        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(List.of(), AGE, LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("al menos un anillo");
    }

    @Test
    void rechaza_mas_anillos_que_el_tope() {
        List<DispatchRing> seven = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            seven.add(ring(i, i * 1000, 5, 10));
        }

        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(seven, AGE, LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("más de 6 anillos");
    }

    @Test
    void acepta_exactamente_el_maximo_de_anillos() {
        List<DispatchRing> six = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            six.add(ring(i, i * 1000, 5, 10));
        }

        assertThat(DispatchConfigRules.buildPlan(six, AGE, LIMITS).rings()).hasSize(6);
    }

    @Test
    void los_radios_tienen_que_crecer() {
        List<DispatchRing> flat = List.of(ring(1, 3000, 10, 10), ring(2, 3000, 10, 10));

        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(flat, AGE, LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("mayor");
    }

    @Test
    void rechaza_un_radio_fuera_del_rango_de_la_base() {
        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(List.of(ring(1, 99, 10, 10)), AGE, LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("radio del anillo 1");
        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(List.of(ring(1, 50_001, 10, 10)), AGE, LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("radio del anillo 1");
    }

    @Test
    void acepta_los_extremos_del_rango_del_radio() {
        assertThat(DispatchConfigRules.buildPlan(List.of(ring(1, 100, 10, 10)), AGE, LIMITS)).isNotNull();
        assertThat(DispatchConfigRules.buildPlan(List.of(ring(1, 50_000, 10, 10)), AGE, LIMITS)).isNotNull();
    }

    @Test
    void rechaza_una_espera_fuera_de_rango() {
        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(List.of(ring(1, 3000, 0, 10)), AGE, LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("espera del anillo 1");
        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(List.of(ring(1, 3000, 121, 10)), AGE, LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("espera del anillo 1");
    }

    @Test
    void rechaza_un_maximo_de_conductores_fuera_de_rango() {
        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(List.of(ring(1, 3000, 10, 0)), AGE, LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("conductores del anillo 1");
        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(List.of(ring(1, 3000, 10, 101)), AGE, LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("conductores del anillo 1");
    }

    @Test
    void la_busqueda_completa_no_puede_pasar_del_tope() {
        // 3 x 41 s = 123 s > 120 s
        List<DispatchRing> slow = List.of(ring(1, 1000, 41, 10), ring(2, 2000, 41, 10), ring(3, 3000, 41, 10));

        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(slow, AGE, LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("120 segundos");
    }

    @Test
    void la_busqueda_de_exactamente_el_tope_es_valida() {
        List<DispatchRing> exact = List.of(ring(1, 1000, 60, 10), ring(2, 2000, 60, 10));

        assertThat(DispatchConfigRules.buildPlan(exact, AGE, LIMITS).totalWait()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void la_frescura_de_la_ubicacion_respeta_el_minimo_configurado_y_el_maximo_de_la_base() {
        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(valid(), Duration.ofSeconds(29), LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("frescura");
        assertThatThrownBy(() -> DispatchConfigRules.buildPlan(valid(), Duration.ofSeconds(601), LIMITS))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("frescura");

        assertThat(DispatchConfigRules.buildPlan(valid(), Duration.ofSeconds(30), LIMITS)).isNotNull();
        assertThat(DispatchConfigRules.buildPlan(valid(), Duration.ofSeconds(600), LIMITS)).isNotNull();
    }
}
