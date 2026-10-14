package com.areascript.taxiapp.user.domain;

// Los datos del vehículo que el conductor declara al registrarse o editar su
// perfil. El estado de verificación NO va acá: lo decide el servidor.
public record VehicleData(
        String plate,
        String brand,
        String model,
        int year,
        String color,
        String registrationNumber
) {
}
