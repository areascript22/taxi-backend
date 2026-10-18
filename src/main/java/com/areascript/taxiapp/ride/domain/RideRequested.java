package com.areascript.taxiapp.ride.domain;

// Hecho: un pasajero pidió un taxi y quedó una solicitud PENDING. Lo escucha el
// despacho, que busca a quién ofrecérsela; la feature de carreras no sabe que
// existe.
//
// Lleva lo que hace falta para ofrecerla sin tener que volver a leer la
// carrera. Se publica DESPUÉS de confirmada la escritura.
public record RideRequested(
        String rideId,
        String passengerId,
        String passengerName,
        double pickupLatitude,
        double pickupLongitude,
        String pickupAddress,
        String pickupSector
) {
}
