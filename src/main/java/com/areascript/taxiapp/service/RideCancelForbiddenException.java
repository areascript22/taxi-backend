package com.areascript.taxiapp.service;

public class RideCancelForbiddenException extends RuntimeException {

    public RideCancelForbiddenException(String passengerId) {
        super("El llamador no es el pasajero ni el conductor asignado de passengerId=" + passengerId);
    }
}
