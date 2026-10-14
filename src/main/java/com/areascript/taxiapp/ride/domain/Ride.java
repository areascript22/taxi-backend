package com.areascript.taxiapp.ride.domain;

import java.util.Map;

// Una carrera tal como está guardada, en el subconjunto que las reglas
// necesitan, más el documento completo.
//
// `status` es null si el valor guardado no es un estado conocido; en ese caso
// ninguna de las preguntas "¿está en curso?" da verdadero.
// `driverId` es null mientras no hay conductor asignado; `cancelledBy` es null
// mientras no se canceló. `pickupLatitude/pickupLongitude` pueden ser null.
//
// `document` es el nodo crudo: lo devuelven tal cual los endpoints de "viaje
// activo" (RideEntity.fromJson en passenger_app lo parsea así), porque las apps
// escriben campos propios en él que el servidor no modela.
public record Ride(
        String passengerId,
        String rideId,
        RideStatus status,
        String driverId,
        String cancelledBy,
        Double pickupLatitude,
        Double pickupLongitude,
        Map<String, Object> document
) {

    public boolean isInProgress() {
        return status != null && status.isInProgress();
    }

    public boolean isActiveForPassenger() {
        return status != null && status.isActiveForPassenger();
    }

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }
}
