package com.areascript.taxiapp.dto;

public record DriverDTO(
        String uid,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        String photoUrl,
        String fcmToken,
        double rating,
        String role,
        String createdAt,
        String updatedAt,
        VehicleDTO vehicle
) {
}
