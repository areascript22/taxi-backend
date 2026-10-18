package com.areascript.taxiapp.driveradmin.domain;

import java.time.Instant;

// Vehículo de un conductor. Los textos ausentes son "" (nunca null) y las
// fechas ausentes son null.
public record Vehicle(
        String vehicleId,
        String driverId,
        String plate,
        String brand,
        String model,
        int year,
        String color,
        String registrationNumber,
        String verificationStatus,
        Instant createdAt,
        Instant updatedAt
) {
}
