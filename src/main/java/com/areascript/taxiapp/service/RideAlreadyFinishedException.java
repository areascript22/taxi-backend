package com.areascript.taxiapp.service;

public class RideAlreadyFinishedException extends RuntimeException {

    public RideAlreadyFinishedException(String passengerId) {
        super("La carrera de passengerId=" + passengerId + " ya estaba cancelada o finalizada");
    }
}
