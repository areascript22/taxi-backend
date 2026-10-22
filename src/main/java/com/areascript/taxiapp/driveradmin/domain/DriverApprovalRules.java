package com.areascript.taxiapp.driveradmin.domain;

// En qué estado de aprobación queda un conductor al guardar su perfil.
//
// Regla pura: recibe el estado actual y decide, sin leer ni escribir nada. Quien
// la aplica (el adaptador de perfiles) lo hace dentro de la misma transacción
// que guarda el perfil.
//
// Por qué existe la excepción del fundador: la aprobación es la puerta para
// CONDUCIR (el servidor ya la exige para ponerse disponible y aceptar carreras),
// pero nadie está por encima del superusuario para aprobarlo, y ModerationPolicy
// prohíbe a propósito aprobar a un superusuario por la API. Sin esta excepción,
// el fundador de una base nueva quedaría en "pendiente" para siempre. Quién es
// el fundador lo dice la configuración del servidor (taxiapp.admin.founder-uid),
// no un cliente: ese es el punto de confianza raíz.
public final class DriverApprovalRules {

    private DriverApprovalRules() {
    }

    // `current` es null cuando el perfil todavía no existe (alta).
    //
    //   - autoApprove: queda APPROVED, venga de PENDING, de REJECTED o de un alta.
    //   - si no: un alta nace PENDING (el cliente no decide su aprobación) y una
    //     edición conserva el estado que ya tenía.
    public static ApprovalStatus afterProfileSave(ApprovalStatus current, boolean autoApprove) {
        if (autoApprove) {
            return ApprovalStatus.APPROVED;
        }
        return current == null ? ApprovalStatus.PENDING : current;
    }
}
