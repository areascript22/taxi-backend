package com.areascript.taxiapp.driveradmin.domain;

import com.areascript.taxiapp.common.error.ForbiddenException;
import com.areascript.taxiapp.identity.domain.AccountRoles;

// La jerarquía que comparten todas las acciones de moderación (cambiar rol,
// aprobar/rechazar, bloquear/desbloquear, eliminar): nadie -- ni otro
// superuser -- puede actuar sobre un superuser, y un admin normal no puede
// actuar sobre otro admin (solo un superuser puede).
public final class ModerationPolicy {

    private ModerationPolicy() {
    }

    // Lanza ForbiddenException si quien actúa no puede hacerlo sobre `target`.
    public static void assertCanModerate(AccountRoles target, boolean callerIsSuperUser) {
        if (target.superUser()) {
            throw new ForbiddenException("No se puede realizar esta acción sobre un superuser");
        }
        if (target.admin() && !callerIsSuperUser) {
            throw new ForbiddenException("Un admin no puede realizar esta acción sobre otro admin");
        }
    }
}
