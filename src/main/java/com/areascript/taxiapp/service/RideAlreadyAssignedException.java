package com.areascript.taxiapp.service;

public class RideAlreadyAssignedException extends RuntimeException {

    public RideAlreadyAssignedException(String passengerId) {
        super("La carrera de passengerId=" + passengerId + " ya fue tomada por otro conductor o ya no está disponible");
    }
}
