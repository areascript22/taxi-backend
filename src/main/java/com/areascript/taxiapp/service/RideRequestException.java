package com.areascript.taxiapp.service;

public class RideRequestException extends RuntimeException {

    public RideRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
