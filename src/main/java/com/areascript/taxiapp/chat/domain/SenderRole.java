package com.areascript.taxiapp.chat.domain;

// Quién escribió un mensaje. El valor de red es el que guarda Firestore y lee
// la app (campo senderRole).
public enum SenderRole {
    PASSENGER("passenger"),
    DRIVER("driver");

    private final String wireValue;

    SenderRole(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
