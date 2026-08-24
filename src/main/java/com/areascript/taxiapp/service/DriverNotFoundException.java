package com.areascript.taxiapp.service;

public class DriverNotFoundException extends RuntimeException {

    public DriverNotFoundException(String uid) {
        super("No existe ningún driver con uid: " + uid);
    }
}
