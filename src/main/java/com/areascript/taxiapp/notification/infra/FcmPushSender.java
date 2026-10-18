package com.areascript.taxiapp.notification.infra;

import com.areascript.taxiapp.notification.domain.PushContent;
import com.areascript.taxiapp.notification.domain.PushSender;
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
import org.springframework.stereotype.Component;

// Entrega los push por Firebase Cloud Messaging.
@Component
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    // Debe calzar con el canal Android que crean driver_app/passenger_app
    // (PushNotificationsServiceImpl) y con el
    // "com.google.firebase.messaging.default_notification_channel_id" de
    // cada AndroidManifest.xml -- ese canal ya está creado con
    // Importance.high; lo que faltaba era decirle a FCM que envíe el
    // mensaje con prioridad alta y apuntando explícitamente a ese canal,
    // sin lo cual Android puede demorar la entrega o no mostrar heads-up
    // aunque el canal sea de importancia alta.
    static final String ANDROID_CHANNEL_ID = "high_importance_channel";

    private final FirebaseMessaging firebaseMessaging;

    public FcmPushSender(FirebaseMessaging firebaseMessaging) {
        this.firebaseMessaging = firebaseMessaging;
    }

    @Override
    public void send(String deviceToken, PushContent content) {
        Message message = Message.builder()
                .setToken(deviceToken)
                .setNotification(Notification.builder()
                        .setTitle(content.title())
                        .setBody(content.body())
                        .build())
                .putData("title", content.title())
                .putData("subtitle", content.body())
                .putData("route", content.route())
                // FCM exige valores no nulos en putData -- type/rideId son
                // los únicos campos opcionales del payload (ver PushMessage).
                .putData("type", content.type() != null ? content.type() : "")
                .putData("rideId", content.rideId() != null ? content.rideId() : "")
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
                                        .setTitle(content.title())
                                        .setBody(content.body())
                                        .build())
                                .setSound("default")
                                .build())
                        .build())
                .build();

        try {
            firebaseMessaging.send(message);
        } catch (FirebaseMessagingException e) {
            log.error("NotificationDebug | Error al enviar push '{}' a token={}: {}", content.title(), deviceToken, e.getMessage(), e);
        }
    }
}
