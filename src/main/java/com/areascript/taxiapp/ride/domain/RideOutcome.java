package com.areascript.taxiapp.ride.domain;

// Resultado de aplicar una RideTransition de forma atómica.
//
//   committed  true si la escritura se confirmó (incluye "sin cambios"); false
//              si la regla rechazó la operación.
//   ride       cómo quedó la carrera, o null si no existe.
//   decision   la última decisión que tomó la regla; sirve para saber POR QUÉ
//              se rechazó (RideChange.Rejected.reason()).
public record RideOutcome(boolean committed, Ride ride, RideChange decision) {

    // true si la regla rechazó por falta de permiso (y no por el estado).
    public boolean rejectedAsForbidden() {
        return decision instanceof RideChange.Rejected rejected
                && rejected.reason() == RideChange.Reason.FORBIDDEN;
    }
}
