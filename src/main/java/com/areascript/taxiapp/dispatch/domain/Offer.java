package com.areascript.taxiapp.dispatch.domain;

import java.time.Instant;

// Un pedido ofrecido a un conductor: lo mínimo para que decida si lo toma,
// SIN datos personales de más (no lleva ni el id del pasajero más allá de la
// clave de la carrera). `expiresAt` es cuándo deja de tener sentido mostrarlo.
public record Offer(
        String rideId,
        String passengerId,
        String passengerName,
        double pickupLatitude,
        double pickupLongitude,
        String pickupAddress,
        String pickupSector,
        int ring,
        double distanceMeters,
        Instant expiresAt
) {
}
