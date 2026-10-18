package com.areascript.taxiapp.user.api;

// Cuerpo de PUT /api/me/driver-profile: datos personales y vehículo. No lleva
// aprobación, rol, bloqueo ni calificación: son del servidor.
//
// `year` es Integer para poder distinguir "no vino" (400) de un 0 explícito.
public record DriverProfileRequest(
        String firstName,
        String lastName,
        String phoneNumber,
        String photoUrl,
        Vehicle vehicle
) {

    public record Vehicle(
            String plate,
            String brand,
            String model,
            Integer year,
            String color,
            String registrationNumber
    ) {
    }
}
