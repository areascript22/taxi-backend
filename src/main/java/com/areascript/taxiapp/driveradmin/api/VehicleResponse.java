package com.areascript.taxiapp.driveradmin.api;

import com.areascript.taxiapp.driveradmin.domain.Vehicle;

// JSON del vehículo que consume driver_app (VehicleModel.fromJson). Los
// nombres de los campos son el contrato con la app: no renombrar.
public record VehicleResponse(
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
    static VehicleResponse from(Vehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        return new VehicleResponse(
                vehicle.vehicleId(),
                vehicle.driverId(),
                vehicle.plate(),
                vehicle.brand(),
                vehicle.model(),
                vehicle.year(),
                vehicle.color(),
                vehicle.registrationNumber(),
                vehicle.verificationStatus(),
                vehicle.createdAt() == null ? null : vehicle.createdAt().toString(),
                vehicle.updatedAt() == null ? null : vehicle.updatedAt().toString()
        );
    }
}
