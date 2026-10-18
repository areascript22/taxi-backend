package com.areascript.taxiapp.ride.domain;

// Hecho: un conductor aceptó una carrera. Lo publica quien asigna al
// conductor y lo escuchan las features que reaccionan a eso (hoy, el chat
// abre el hilo de la carrera) sin que ride tenga que conocerlas.
//
// Se publica DESPUÉS de que la asignación quedó confirmada: nada de lo que
// reacciona al evento puede revertirla.
public record RideAccepted(String rideId, String passengerId, String driverId) {
}
