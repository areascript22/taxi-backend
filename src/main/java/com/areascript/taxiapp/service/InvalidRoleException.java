package com.areascript.taxiapp.service;

public class InvalidRoleException extends RuntimeException {

    public InvalidRoleException(String role) {
        super("Rol inválido: '" + role + "'. Valores permitidos: admin, driver");
    }
}
