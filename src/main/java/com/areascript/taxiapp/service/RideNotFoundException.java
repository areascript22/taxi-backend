package com.areascript.taxiapp.service;

public class RideNotFoundException extends RuntimeException {

    public RideNotFoundException(String passengerId) {
        super("No existe una solicitud de carrera activa para passengerId: " + passengerId);
    }
}
