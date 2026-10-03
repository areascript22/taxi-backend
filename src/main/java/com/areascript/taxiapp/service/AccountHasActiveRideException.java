package com.areascript.taxiapp.service;

public class AccountHasActiveRideException extends RuntimeException {

    public AccountHasActiveRideException(String uid) {
        super("No se puede eliminar la cuenta uid=" + uid + ": tiene un viaje activo");
    }
}
