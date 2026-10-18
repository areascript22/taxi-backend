package com.areascript.taxiapp.notification.domain;

// El push ya traducido y listo para entregar: lo que recibe un PushSender.
// Es lo único que el transporte necesita saber; no conoce claves ni idiomas.
//
// `type` y `rideId` pueden ser null (ver PushMessage).
public record PushContent(
        String title,
        String body,
        String route,
        String type,
        String rideId
) {
}
