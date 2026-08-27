package com.areascript.taxiapp.dto;

public record VehicleDTO(
        String vehicleId,
        String driverId,
        String plate,
        String brand,
        String model,
        int year,
        String color,
        String registrationNumber,
        String verificationStatus,
        String createdAt,
        String updatedAt
) {
}
