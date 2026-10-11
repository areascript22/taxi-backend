package com.areascript.taxiapp.user.domain;

import java.util.UUID;

// La identidad de una persona en el sistema: una fila de users.
//
// `id` es la identidad interna (la que referencian las demás tablas);
// `firebaseUid` es la externa, la que trae el token. `displayName` es el
// nombre completo ("" si no se conoce).
public record UserAccount(
        UUID id,
        String firebaseUid,
        String email,
        String displayName,
        UserRole role,
        boolean blocked
) {
}
