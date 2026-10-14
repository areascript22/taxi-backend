package com.areascript.taxiapp.dispatch.domain;

// Un conductor disponible y a qué distancia está del punto buscado, en metros
// en línea recta.
public record NearbyDriver(String driverUid, double distanceMeters) {
}
