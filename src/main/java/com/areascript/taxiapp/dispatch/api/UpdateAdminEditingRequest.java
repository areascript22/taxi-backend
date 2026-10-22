package com.areascript.taxiapp.dispatch.api;

import com.areascript.taxiapp.common.error.InvalidInputException;

// Enciende o apaga "los admins pueden editar la configuración del despacho".
public record UpdateAdminEditingRequest(Boolean enabled) {

    public boolean requireEnabled() {
        if (enabled == null) {
            throw new InvalidInputException("Falta enabled");
        }
        return enabled;
    }
}
