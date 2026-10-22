package com.areascript.taxiapp.dispatch.domain;

import java.time.Duration;
import java.util.List;

// Cómo se busca un conductor: los anillos, del más cercano al más lejano, y qué
// tan reciente tiene que ser una ubicación para considerarla.
//
// Es una FOTO de la configuración: cada carrera arranca con la suya y la
// conserva hasta el final, así que un administrador que cambie los radios a
// mitad de una búsqueda no la altera.
//
// Reglas que la base no puede expresar (son entre filas) y se validan acá:
//   - los números de anillo son consecutivos desde 1
//   - los radios son estrictamente crecientes (un anillo que no amplía nada no
//     busca a nadie nuevo)
public record DispatchPlan(List<DispatchRing> rings, Duration locationMaxAge) {

    public DispatchPlan {
        rings = List.copyOf(rings);
        for (int i = 0; i < rings.size(); i++) {
            DispatchRing ring = rings.get(i);
            if (ring.number() != i + 1) {
                throw new IllegalArgumentException(
                        "Los anillos deben ser consecutivos desde 1: en la posición " + (i + 1)
                                + " hay el anillo " + ring.number());
            }
            if (i > 0 && ring.radiusMeters() <= rings.get(i - 1).radiusMeters()) {
                throw new IllegalArgumentException(
                        "El radio del anillo " + ring.number() + " (" + ring.radiusMeters()
                                + " m) debe ser mayor que el del anillo " + (ring.number() - 1));
            }
        }
    }

    // Sin anillos no se despacha nada (el despacho queda desactivado de hecho).
    public static DispatchPlan empty() {
        return new DispatchPlan(List.of(), Duration.ofSeconds(45));
    }

    public boolean isEmpty() {
        return rings.isEmpty();
    }

    // Lo que tarda la búsqueda completa, sin contar el tiempo de cada consulta.
    public Duration totalWait() {
        return rings.stream().map(DispatchRing::waitTime).reduce(Duration.ZERO, Duration::plus);
    }

    // Holgura entre que termina el último anillo y que el sistema vence la
    // solicitud: el cierre "no hay vehículos" del despacho debe ganar siempre.
    public static final Duration EXPIRY_MARGIN = Duration.ofSeconds(5);

    // Cuánto espera el sistema antes de vencer una solicitud sin conductor.
    //
    // No puede ser menor que lo que dura la búsqueda por anillos: si el
    // administrador alarga las esperas, un vencimiento fijo mataría la solicitud
    // a mitad de camino y los anillos siguientes nunca se ofrecerían. Es el MAYOR
    // entre el mínimo configurado y la búsqueda completa más un margen. Con un
    // plan vacío no hay búsqueda y vale el mínimo.
    public Duration requestExpiry(Duration configuredMinimum) {
        if (isEmpty()) {
            return configuredMinimum;
        }
        Duration search = totalWait().plus(EXPIRY_MARGIN);
        return search.compareTo(configuredMinimum) > 0 ? search : configuredMinimum;
    }
}
