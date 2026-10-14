package com.areascript.taxiapp.ride.domain;

// Hecho: una carrera que ya tenía conductor terminó (finalizada o cancelada).
// Lo publica quien la termina y lo escuchan las features que reaccionan a eso
// (hoy, el despacho vuelve a marcar disponible al conductor).
//
// No se publica para una solicitud que venció sin conductor: nadie quedó
// ocupado. Se publica DESPUÉS de confirmada la escritura; nada de lo que
// reacciona puede revertirla.
public record RideEnded(String rideId, String passengerId, String driverId) {
}
