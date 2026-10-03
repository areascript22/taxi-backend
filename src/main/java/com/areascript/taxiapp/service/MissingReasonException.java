package com.areascript.taxiapp.service;

public class MissingReasonException extends RuntimeException {

    public MissingReasonException(String message) {
        super(message);
    }
}
