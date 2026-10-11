package com.areascript.taxiapp.user.api;

// Cuerpo de PUT /api/me/device. `app` es "driver" o "passenger"; fcmToken y
// language son opcionales, pero al menos uno tiene que venir. Un campo ausente
// no se modifica.
public record DeviceRequest(String app, String fcmToken, String language) {
}
