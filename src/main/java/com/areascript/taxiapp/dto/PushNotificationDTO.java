package com.areascript.taxiapp.dto;

// type/rideId son opcionales (pueden ir null) -- solo los usan los pushes de
// chat por ahora, para que el cliente pueda decidir de qué carrera es el
// mensaje y navegar directo al chat en vez de a la pantalla genérica de
// `route`. Los pushes de estado de carrera (aceptada/cancelada/etc.) los
// dejan en null.
public record PushNotificationDTO(
        String title,
        String subtitle,
        String route,
        String type,
        String rideId
) {
}
