package com.areascript.taxiapp.ride.domain;

// Hecho: una solicitud que todavía NO tenía conductor dejó de estar disponible
// (la canceló el pasajero o venció). Quien la estaba ofreciendo (el despacho)
// tiene que dejar de hacerlo y retirar las ofertas.
//
// No se publica cuando la solicitud termina por tener conductor (eso es
// RideAccepted) ni cuando termina una carrera ya asignada (RideEnded).
public record RideRequestWithdrawn(String rideId, String passengerId) {
}
