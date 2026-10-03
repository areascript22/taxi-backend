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
        String approvalStatus,
        boolean isBlocked,
        String blockReason,
        String blockedAt,
        String blockedBy,
        String rejectionReason,
        String createdAt,
        String updatedAt,
        VehicleDTO vehicle
) {
}
