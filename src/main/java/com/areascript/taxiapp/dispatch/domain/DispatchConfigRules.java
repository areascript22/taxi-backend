package com.areascript.taxiapp.dispatch.domain;

import com.areascript.taxiapp.common.error.InvalidInputException;

import java.time.Duration;
import java.util.List;

// Valida y arma el plan que un administrador quiere guardar.
//
// Un plan inválido guardado no es un error visible: el despacho lo descarta y
// deja de despachar sin avisar (ver JpaDispatchPlans). Por eso todo se valida
// ANTES de escribir y se rechaza con un mensaje que el administrador pueda
// corregir.
public final class DispatchConfigRules {

    private DispatchConfigRules() {
    }

    // Lanza InvalidInputException con el primer problema encontrado.
    public static DispatchPlan buildPlan(List<DispatchRing> rings, Duration locationMaxAge, DispatchConfigLimits limits) {
        if (rings == null || rings.isEmpty()) {
            // Sin anillos no se despacha nada: apagar el despacho no es algo que
            // se haga dejando la lista vacía.
            throw new InvalidInputException("Tiene que haber al menos un anillo");
        }
        if (rings.size() > limits.maxRings()) {
            throw new InvalidInputException("No puede haber más de " + limits.maxRings() + " anillos");
        }

        for (DispatchRing ring : rings) {
            requireRange(ring.radiusMeters(), DispatchConfigLimits.MIN_RADIUS_METERS,
                    DispatchConfigLimits.MAX_RADIUS_METERS, "El radio del anillo " + ring.number() + " (en metros)");
            requireRange((int) ring.waitTime().toSeconds(), DispatchConfigLimits.MIN_WAIT_SECONDS,
                    DispatchConfigLimits.MAX_WAIT_SECONDS, "La espera del anillo " + ring.number() + " (en segundos)");
            requireRange(ring.maxDrivers(), DispatchConfigLimits.MIN_MAX_DRIVERS,
                    DispatchConfigLimits.MAX_MAX_DRIVERS, "El máximo de conductores del anillo " + ring.number());
        }

        long minAge = limits.minLocationMaxAge().toSeconds();
        requireRange((int) locationMaxAge.toSeconds(), (int) minAge,
                DispatchConfigLimits.MAX_LOCATION_MAX_AGE_SECONDS, "La frescura de la ubicación (en segundos)");

        DispatchPlan plan;
        try {
            // Consecutivos desde 1 y radios estrictamente crecientes.
            plan = new DispatchPlan(rings, locationMaxAge);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException(e.getMessage());
        }

        if (plan.totalWait().compareTo(limits.maxTotalWait()) > 0) {
            throw new InvalidInputException("La búsqueda completa no puede durar más de "
                    + limits.maxTotalWait().toSeconds() + " segundos (suma de las esperas de los anillos)");
        }
        return plan;
    }

    private static void requireRange(int value, int min, int max, String what) {
        if (value < min || value > max) {
            throw new InvalidInputException(what + " debe estar entre " + min + " y " + max);
        }
    }
}
