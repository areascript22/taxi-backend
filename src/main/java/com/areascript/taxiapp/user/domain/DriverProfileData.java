package com.areascript.taxiapp.user.domain;

// Lo que un conductor declara de sí mismo: sus datos personales y su vehículo.
// Es todo lo que el cliente puede mandar: el estado de aprobación, el rol, el
// bloqueo y la calificación los decide el servidor y no pasan por acá.
//
// `photoUrl` es null si no tiene foto (la sube a Storage y manda la URL).
public record DriverProfileData(
        String firstName,
        String lastName,
        String phoneNumber,
        String photoUrl,
        VehicleData vehicle
) {
}
