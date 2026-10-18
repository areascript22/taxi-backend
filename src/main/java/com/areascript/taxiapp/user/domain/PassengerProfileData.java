package com.areascript.taxiapp.user.domain;

// Lo que un pasajero declara de sí mismo. `photoUrl` es null si no tiene foto.
public record PassengerProfileData(
        String firstName,
        String lastName,
        String photoUrl
) {
}
