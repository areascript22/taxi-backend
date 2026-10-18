package com.areascript.taxiapp.driveradmin.api;

import com.areascript.taxiapp.driveradmin.domain.Driver;

// JSON del conductor que consume el panel de driver_app (AdminDriverModel.
// fromJson). Los nombres de los campos son el contrato con la app: no
// renombrar. Las fechas viajan como texto ISO-8601.
public record DriverResponse(
        String uid,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        String photoUrl,
        String fcmToken,
        double rating,
        String role,
        String approvalStatus,
        boolean isBlocked,
        String blockReason,
        String blockedAt,
        String blockedBy,
        String rejectionReason,
        String createdAt,
        String updatedAt,
        VehicleResponse vehicle
) {
    static DriverResponse from(Driver driver) {
        return new DriverResponse(
                driver.uid(),
                driver.firstName(),
                driver.lastName(),
                driver.email(),
                driver.phoneNumber(),
                driver.photoUrl(),
                driver.fcmToken(),
                driver.rating(),
                driver.role().wireValue(),
                driver.approvalStatus().wireValue(),
                driver.blocked(),
                driver.blockReason(),
                driver.blockedAt() == null ? null : driver.blockedAt().toString(),
                driver.blockedBy(),
                driver.rejectionReason(),
                driver.createdAt() == null ? null : driver.createdAt().toString(),
                driver.updatedAt() == null ? null : driver.updatedAt().toString(),
                VehicleResponse.from(driver.vehicle())
        );
    }
}
