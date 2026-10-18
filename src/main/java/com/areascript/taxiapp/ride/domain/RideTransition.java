package com.areascript.taxiapp.ride.domain;

// Una regla de cambio sobre una carrera: dado el estado actual (null si la
// carrera no existe), decide qué hacer. Tiene que ser pura: el repositorio
// puede invocarla varias veces con datos distintos hasta que la escritura
// atómica se confirma.
@FunctionalInterface
public interface RideTransition {

    RideChange apply(Ride current);
}
