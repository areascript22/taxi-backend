package com.areascript.taxiapp.user.domain;

// Lo propio del conductor (fila de drivers). La identidad está en UserAccount.
// `photoUrl`, `language` y `vehicle` son null cuando no hay valor.
public record DriverProfile(
        String phoneNumber,
        String photoUrl,
        double rating,
        String approvalStatus,
        String language,
        VehicleSummary vehicle,
        boolean online,
        boolean onRide
) {
}
