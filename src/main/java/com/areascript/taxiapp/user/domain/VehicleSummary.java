package com.areascript.taxiapp.user.domain;

// El vehículo con el que trabaja hoy un conductor.
public record VehicleSummary(
        String vehicleId,
        String plate,
        String brand,
        String model,
        int year,
        String color,
        String registrationNumber,
        String verificationStatus
) {
}
