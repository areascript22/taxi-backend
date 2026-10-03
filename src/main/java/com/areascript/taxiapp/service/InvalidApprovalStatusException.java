package com.areascript.taxiapp.service;

public class InvalidApprovalStatusException extends RuntimeException {

    public InvalidApprovalStatusException(String status) {
        super("Estado de aprobación inválido: '" + status + "'. Valores permitidos: pending, approved, rejected");
    }
}
