package com.areascript.taxiapp.service;

// Se lanza cuando falla la verificación de Firebase Auth del objetivo antes
// de aplicar una acción de moderación (aprobar/rechazar/bloquear/
// desbloquear/cambiar rol) -- ver DriverAdminService.assertCanModerate.
public class DriverModerationException extends RuntimeException {

    public DriverModerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
