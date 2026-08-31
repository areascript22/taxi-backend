package com.areascript.taxiapp.service;

import com.areascript.taxiapp.dto.PushNotificationDTO;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.ApsAlert;
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

    // Debe calzar con el canal Android que crean driver_app/passenger_app
    // (PushNotificationsServiceImpl) y con el
    // "com.google.firebase.messaging.default_notification_channel_id" de
    // cada AndroidManifest.xml -- ese canal ya está creado con
    // Importance.high; lo que faltaba era decirle a FCM que envíe el
    // mensaje con prioridad alta y apuntando explícitamente a ese canal,
    // sin lo cual Android puede demorar la entrega o no mostrar heads-up
    // aunque el canal sea de importancia alta.
    private static final String ANDROID_CHANNEL_ID = "high_importance_channel";

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
                // Prioridad alta + canal/prioridad de la notificación en
                // alto: sin esto, Android puede mostrar el push en modo
                // "silencioso" (solo en la bandeja) en vez de heads-up,
                // incluso con background/terminated, y puede demorar la
                // entrega si el dispositivo está en Doze.
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setChannelId(ANDROID_CHANNEL_ID)
                                .setPriority(AndroidNotification.Priority.HIGH)
                                .build())
                        .build())
                // iOS: apns-priority=10 exige entrega inmediata (requerido
                // para alertas visibles); sin esto APNs puede agrupar/
                // demorar el push como si fuera de baja prioridad.
                .setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-priority", "10")
                        .setAps(Aps.builder()
                                .setAlert(ApsAlert.builder()
                                        .setTitle(payload.title())
                                        .setBody(payload.subtitle())
                                        .build())
                                .setSound("default")
                                .build())
                        .build())
                .build();

        try {
            firebaseMessaging.send(message);
        } catch (FirebaseMessagingException e) {
            log.error("NotificationDebug | Error al enviar push '{}' a token={}: {}", payload.title(), token, e.getMessage(), e);
        }
    }
}
