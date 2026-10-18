package com.areascript.taxiapp.chat.domain;

// Lo que el chat necesita saber de una carrera para decidir si alguien puede
// escribir: de quién es, quién es el conductor asignado y si sigue en curso.
//
// `driverId` es null mientras no hay conductor asignado. `active` es true solo
// con un conductor asignado y el viaje sin terminar: con la solicitud todavía
// 'pending' no hay con quién chatear.
public record ChatRide(String rideId, String passengerId, String driverId, boolean active) {
}
