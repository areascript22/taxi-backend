package com.areascript.taxiapp.dispatch.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Las reglas del plan que la base no puede expresar (son entre filas).
class DispatchPlanTest {

    private static DispatchRing ring(int number, int radius) {
        return new DispatchRing(number, radius, Duration.ofSeconds(10), 10);
    }

    private static DispatchPlan plan(DispatchRing... rings) {
        return new DispatchPlan(List.of(rings), Duration.ofSeconds(45));
    }

    @Test
    void un_plan_valido_suma_la_espera_de_todos_los_anillos() {
        DispatchPlan plan = plan(ring(1, 3000), ring(2, 6000), ring(3, 9000));

        assertThat(plan.rings()).hasSize(3);
        assertThat(plan.totalWait()).isEqualTo(Duration.ofSeconds(30));
        assertThat(plan.isEmpty()).isFalse();
    }

    @Test
    void el_plan_vacio_no_despacha() {
        assertThat(DispatchPlan.empty().isEmpty()).isTrue();
        assertThat(DispatchPlan.empty().totalWait()).isEqualTo(Duration.ZERO);
    }

    @Test
    void los_radios_tienen_que_crecer_estrictamente() {
        assertThatThrownBy(() -> plan(ring(1, 3000), ring(2, 3000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mayor");
        assertThatThrownBy(() -> plan(ring(1, 6000), ring(2, 3000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void los_numeros_de_anillo_son_consecutivos_desde_uno() {
        assertThatThrownBy(() -> plan(ring(2, 3000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consecutivos");
        assertThatThrownBy(() -> plan(ring(1, 3000), ring(3, 6000)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
