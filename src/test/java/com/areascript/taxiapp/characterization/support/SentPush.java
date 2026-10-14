package com.areascript.taxiapp.characterization.support;

import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;

import java.lang.reflect.Field;
import java.util.Map;

// Lo que se puede afirmar de un push ya enviado. La clase Message de
// firebase-admin no expone getters, así que se lee por reflexión: es solo
// para tests y el día que cambie la versión del SDK, el fallo será obvio acá.
public record SentPush(String token, String title, String body, Map<String, String> data) {

    @SuppressWarnings("unchecked")
    public static SentPush from(Message message) {
        Notification notification = (Notification) read(message, "notification");
        return new SentPush(
                (String) read(message, "token"),
                notification == null ? null : (String) read(notification, "title"),
                notification == null ? null : (String) read(notification, "body"),
                (Map<String, String>) read(message, "data")
        );
    }

    private static Object read(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "No se pudo leer " + target.getClass().getSimpleName() + "." + fieldName
                            + " -- ¿cambió la versión de firebase-admin?", e);
        }
    }
}
