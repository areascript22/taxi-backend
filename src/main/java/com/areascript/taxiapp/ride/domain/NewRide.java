package com.areascript.taxiapp.ride.domain;

// Una solicitud de carrera recién pedida, lista para guardar con estado
// PENDING. El nombre y la foto del pasajero salen del token verificado, no de
// lo que mande el cliente.
//
// rideId mantiene el formato "{passengerId}_{millis}" que generaba antes el
// cliente: driver_app lo sigue usando como clave de identificación en su lista
// de solicitudes entrantes.
public record NewRide(
        String rideId,
        String passengerId,
        String passengerName,
        String passengerPhotoUrl,
        PickupLocation pickup
) {
}
