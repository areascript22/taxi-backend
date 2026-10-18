package com.areascript.taxiapp.dispatch.api;

// Cuerpo de PUT /api/me/driver/availability. `online` es obligatorio.
public record AvailabilityRequest(Boolean online) {
}
