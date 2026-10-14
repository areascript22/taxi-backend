package com.areascript.taxiapp.user.domain;

// Lo propio del pasajero (fila de passengers). `photoUrl` y `language` son null
// cuando no hay valor.
public record PassengerProfile(
        String photoUrl,
        String status,
        String language
) {
}
