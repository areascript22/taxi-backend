package com.areascript.taxiapp.user.domain;

import java.util.UUID;

// La identidad de una persona en el sistema: una fila de users.
//
// `id` es la identidad interna (la que referencian las demas tablas);
// `firebaseUid` es la externa, la que trae el token. Nombre y apellido van
// separados porque la app los necesita asi (formulario de perfil); el nombre
// completo es displayName().
//
// `blockReason` es null si no esta bloqueada.
public record UserAccount(
        UUID id,
        String firebaseUid,
        String email,
        String firstName,
        String lastName,
        UserRole role,
        boolean blocked,
        String blockReason
) {

    // "" si no se conoce ninguno de los dos.
    public String displayName() {
        return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
    }
}