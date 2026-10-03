package com.areascript.taxiapp.service;

// Se lanza al intentar bloquear a un conductor cuyo approvalStatus no es
// "approved" -- bloquear solo tiene sentido sobre una cuenta activa; un
// conductor pending/rejected ya está fuera del sistema por ese motivo.
public class DriverNotApprovedException extends RuntimeException {

    public DriverNotApprovedException(String uid) {
        super("El conductor " + uid + " no está aprobado, no se puede bloquear");
    }
}
