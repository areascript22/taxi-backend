package com.areascript.taxiapp.service;

import com.areascript.taxiapp.dto.PushNotificationDTO;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final FirebaseMessaging firebaseMessaging;

    public NotificationService(FirebaseMessaging firebaseMessaging) {
        this.firebaseMessaging = firebaseMessaging;
    }

    // No relanza: un push que falla no debe tumbar el flujo que lo dispara
    // (ej. una carrera ya aceptada no debe revertirse porque la notificación
    // no llegó). Solo se registra el error.
    public void sendPush(String token, PushNotificationDTO payload) {
        if (token == null || token.isBlank()) {
            log.warn("NotificationDebug | Se omitió el envío del push '{}': token vacío", payload.title());
            return;
        }

        Message message = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder()
                        .setTitle(payload.title())
                        .setBody(payload.subtitle())
                        .build())
                .putData("title", payload.title())
                .putData("subtitle", payload.subtitle())
                .putData("route", payload.route())
                .build();

        try {
            firebaseMessaging.send(message);
        } catch (FirebaseMessagingException e) {
            log.error("NotificationDebug | Error al enviar push '{}' a token={}: {}", payload.title(), token, e.getMessage(), e);
        }
    }
}
