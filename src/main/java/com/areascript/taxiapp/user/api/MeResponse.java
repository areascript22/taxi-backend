package com.areascript.taxiapp.user.api;

import com.areascript.taxiapp.user.domain.DriverProfile;
import com.areascript.taxiapp.user.domain.MyProfile;
import com.areascript.taxiapp.user.domain.PassengerProfile;
import com.areascript.taxiapp.user.domain.VehicleSummary;

// Respuesta de GET /api/me. `driver` y `passenger` son null si la persona no
// tiene perfil en esa app.
public record MeResponse(
        String id,
        String uid,
        String email,
        String displayName,
        String firstName,
        String lastName,
        String role,
        boolean blocked,
        String blockReason,
        Driver driver,
        Passenger passenger
) {

    public record Driver(
            String phoneNumber,
            String photoUrl,
            double rating,
            String approvalStatus,
            String rejectionReason,
            String language,
            Vehicle vehicle,
            boolean online,
            boolean onRide
    ) {
    }

    public record Vehicle(
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

    public record Passenger(
            String photoUrl,
            String status,
            String language
    ) {
    }

    static MeResponse from(MyProfile profile) {
        return new MeResponse(
                profile.account().id().toString(),
                profile.account().firebaseUid(),
                profile.account().email(),
                profile.account().displayName(),
                profile.account().firstName(),
                profile.account().lastName(),
                profile.account().role().code(),
                profile.account().blocked(),
                profile.account().blockReason(),
                profile.driver() == null ? null : driver(profile.driver()),
                profile.passenger() == null ? null : passenger(profile.passenger()));
    }

    private static Driver driver(DriverProfile profile) {
        VehicleSummary vehicle = profile.vehicle();
        return new Driver(
                profile.phoneNumber(),
                profile.photoUrl(),
                profile.rating(),
                profile.approvalStatus(),
                profile.rejectionReason(),
                profile.language(),
                vehicle == null ? null : new Vehicle(
                        vehicle.vehicleId(),
                        vehicle.plate(),
                        vehicle.brand(),
                        vehicle.model(),
                        vehicle.year(),
                        vehicle.color(),
                        vehicle.registrationNumber(),
                        vehicle.verificationStatus()),
                profile.online(),
                profile.onRide());
    }

    private static Passenger passenger(PassengerProfile profile) {
        return new Passenger(profile.photoUrl(), profile.status(), profile.language());
    }
}
