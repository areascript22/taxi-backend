package com.areascript.taxiapp.characterization.support;

import com.areascript.taxiapp.dispatch.domain.DispatchPlan;
import com.areascript.taxiapp.dispatch.domain.DispatchPlans;
import com.areascript.taxiapp.dispatch.domain.DispatchRing;

import java.time.Duration;
import java.util.List;

// La configuración del despacho en memoria, para los tests HTTP (que corren sin
// Postgres).
//
// Por defecto es un plan VACÍO: "no despachar". Así los tests de las carreras no
// ven ofertas ni temporizadores que no les corresponden. Los tests del despacho
// llaman a use(...) con los anillos que necesiten.
public class FakeDispatchPlans implements DispatchPlans {

    private DispatchPlan plan = DispatchPlan.empty();

    @Override
    public DispatchPlan current() {
        return plan;
    }

    public void use(DispatchPlan plan) {
        this.plan = plan;
    }

    // Los anillos de 3, 6 y 9 km con 10 s cada uno: los valores iniciales de la base.
    public static DispatchPlan standard() {
        return new DispatchPlan(List.of(
                new DispatchRing(1, 3000, Duration.ofSeconds(10), 10),
                new DispatchRing(2, 6000, Duration.ofSeconds(10), 10),
                new DispatchRing(3, 9000, Duration.ofSeconds(10), 10)),
                Duration.ofSeconds(45));
    }

    public void clear() {
        plan = DispatchPlan.empty();
    }
}
