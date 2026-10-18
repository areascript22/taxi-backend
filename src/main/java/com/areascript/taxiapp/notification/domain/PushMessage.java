package com.areascript.taxiapp.notification.domain;

// Lo que se quiere avisar, todavía SIN traducir: lleva las KEYS del catálogo
// (messages.properties / messages_en.properties), no el texto. Quien decide el
// idioma es PushNotificationService, que lo resuelve con el campo `language`
// del DESTINATARIO: el texto no se puede armar donde se dispara la acción
// porque el que dispara y el que recibe casi nunca son la misma persona (el
// conductor acepta -> el push es para el pasajero).
//
// `bodyText` es la excepción: contenido escrito por el usuario (el preview de
// un mensaje de chat), que se manda tal cual porque no hay nada que traducir.
// Es `bodyKey` O `bodyText`, nunca los dos -- usar las factories.
//
// type/rideId son opcionales (pueden ir null) -- solo los usan los pushes de
// chat por ahora, para que el cliente pueda decidir de qué carrera es el
// mensaje y navegar directo al chat en vez de a la pantalla genérica de
// `route`. Los pushes de estado de carrera (aceptada/cancelada/etc.) los
// dejan en null.
public record PushMessage(
        String titleKey,
        String bodyKey,
        String bodyText,
        String route,
        String type,
        String rideId
) {
    // Título y cuerpo salen del catálogo: el push es texto nuestro.
    public static PushMessage translated(String titleKey, String bodyKey, String route) {
        return new PushMessage(titleKey, bodyKey, null, route, null, null);
    }

    // El cuerpo es texto del usuario y viaja sin tocar; solo el título se
    // traduce. No se pasa por el catálogo a propósito: resolverlo como
    // argumento de un mensaje lo haría pasar por MessageFormat, que se come
    // las comillas simples y las llaves de lo que escribió el usuario.
    public static PushMessage withUserText(
            String titleKey,
            String bodyText,
            String route,
            String type,
            String rideId
    ) {
        return new PushMessage(titleKey, null, bodyText, route, type, rideId);
    }
}
