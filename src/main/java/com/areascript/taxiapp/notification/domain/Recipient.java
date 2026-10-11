package com.areascript.taxiapp.notification.domain;

// A quién va dirigido un push: un usuario identificado por su uid y por la app
// en la que está registrado (los conductores y los pasajeros viven en
// colecciones distintas y pueden tener el mismo uid de Firebase en ambas).
public record Recipient(Kind kind, String uid) {

    public enum Kind { DRIVER, PASSENGER }

    public static Recipient driver(String uid) {
        return new Recipient(Kind.DRIVER, uid);
    }

    public static Recipient passenger(String uid) {
        return new Recipient(Kind.PASSENGER, uid);
    }
}
