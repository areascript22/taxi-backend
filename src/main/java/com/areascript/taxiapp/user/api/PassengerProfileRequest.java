package com.areascript.taxiapp.user.api;

// Cuerpo de PUT /api/me/passenger-profile.
public record PassengerProfileRequest(
        String firstName,
        String lastName,
        String photoUrl
) {
}
