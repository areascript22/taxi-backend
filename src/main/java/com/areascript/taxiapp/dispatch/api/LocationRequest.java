package com.areascript.taxiapp.dispatch.api;

// Cuerpo de PUT /api/me/driver/location. `passengerId` solo se manda cuando el
// conductor va en una carrera (identifica el nodo donde se publica).
public record LocationRequest(Double latitude, Double longitude, String passengerId) {
}
