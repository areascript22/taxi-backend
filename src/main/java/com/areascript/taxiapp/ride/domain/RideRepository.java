package com.areascript.taxiapp.ride.domain;

import java.util.Optional;

// Puerto de salida: dónde viven las carreras en curso (hoy Realtime Database,
// nodo taxi_requests/{passengerId}: una sola carrera por pasajero). Se queda
// en una base de datos en tiempo real aunque los usuarios migren a Postgres,
// porque las apps escuchan cambios de estado sobre este nodo.
//
// Un fallo del almacenamiento (error, timeout) se traduce a
// ExternalServiceException (common.error).
public interface RideRepository {

    // La carrera del pasajero; vacío si no tiene ninguna.
    Optional<Ride> find(String passengerId);

    // La carrera en curso (conductor asignado, sin terminar) que tiene este
    // conductor; vacío si no tiene ninguna.
    Optional<Ride> findInProgressOf(String driverUid);

    // Aplica la regla de forma atómica: si dos llamadas compiten por la misma
    // carrera, se serializan y cada una ve el resultado de la anterior. La
    // regla puede ejecutarse más de una vez, por eso tiene que ser pura. Crear
    // una solicitud también pasa por acá (RideTransitions.request): es lo que
    // permite rechazarla si el pasajero ya tiene un viaje en curso.
    RideOutcome transact(String passengerId, RideTransition transition);
}
