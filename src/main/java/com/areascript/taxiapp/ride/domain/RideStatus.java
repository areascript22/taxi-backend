package com.areascript.taxiapp.ride.domain;

import java.util.Optional;

// Estado de una carrera. El valor de red es el que guarda Realtime Database y
// el que leen las apps (campo status): no renombrar.
//
//   PENDING -> DRIVER_ASSIGNED -> DRIVER_ARRIVED -> TRIP_STARTED -> TRIP_COMPLETED
//        \______________\________________\_______________\______-> CANCELLED
//
// Todas las transiciones las hace el servidor (ver RideTransitions):
// PENDING -> DRIVER_ASSIGNED (conductor acepta), -> DRIVER_ARRIVED (conductor
// llegó), -> TRIP_STARTED (pasajero va en camino), -> TRIP_COMPLETED
// (conductor finaliza) y cualquier estado no terminal -> CANCELLED.
//
// Las versiones VIEJAS de las apps todavía escriben DRIVER_ARRIVED y
// TRIP_STARTED directo en la base: el servidor las tiene que poder leer como
// siempre.
public enum RideStatus {
    PENDING("pending"),
    DRIVER_ASSIGNED("driverAssigned"),
    DRIVER_ARRIVED("driverArrived"),
    TRIP_STARTED("tripStarted"),
    TRIP_COMPLETED("tripCompleted"),
    CANCELLED("cancelled");

    private final String wireValue;

    RideStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    // Vacío si el valor no es un estado conocido (o es null).
    public static Optional<RideStatus> parse(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        for (RideStatus status : values()) {
            if (status.wireValue.equals(value.toString())) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    // Ya no admite más cambios: el nodo se borra poco después.
    public boolean isTerminal() {
        return this == CANCELLED || this == TRIP_COMPLETED;
    }

    // Con conductor asignado y viaje sin terminar. Es lo que cuenta como
    // "viaje en curso" para el conductor (no hay nada que resumir mientras la
    // solicitud sigue PENDING) y para habilitar el chat.
    //
    // Debe mantenerse en sync con _activeTripStatuses de driver_app.
    public boolean isInProgress() {
        return this == DRIVER_ASSIGNED || this == DRIVER_ARRIVED || this == TRIP_STARTED;
    }

    // Para el pasajero, PENDING también cuenta como "viaje en curso": si cierra
    // la app mientras todavía no hay conductor, al reabrir debe poder resumir
    // el diálogo "Buscando conductor" en vez de perder la solicitud (que sigue
    // viva y cualquier conductor podría aceptar mientras tanto).
    public boolean isActiveForPassenger() {
        return this == PENDING || isInProgress();
    }
}
