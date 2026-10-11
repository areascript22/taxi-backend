package com.areascript.taxiapp.dispatch.domain;

import java.time.Duration;

// Un anillo de búsqueda: a quiénes se les ofrece un pedido (los disponibles a
// menos de `radiusMeters`, hasta `maxDrivers`, del más cercano al más lejano) y
// cuánto se espera antes de ampliar al siguiente.
public record DispatchRing(int number, int radiusMeters, Duration waitTime, int maxDrivers) {
}
