package com.areascript.taxiapp.driveradmin.domain;

import java.time.Instant;

// Un conductor tal como lo ve el panel de administración. Los textos ausentes
// son "" (nunca null); photoUrl, blockReason, blockedBy y rejectionReason son
// null cuando no hay valor, igual que las fechas y el vehículo.
public record Driver(
        String uid,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        String photoUrl,
        String fcmToken,
        double rating,
        DriverRole role,
        ApprovalStatus approvalStatus,
        boolean blocked,
        String blockReason,
        Instant blockedAt,
        String blockedBy,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt,
        Vehicle vehicle
) {
}
