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
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Service;

import java.util.Locale;

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

    // Idioma del push cuando el destinatario no tiene `language` guardado
    // (documento creado antes de que existiera el campo, o usuario que nunca
    // llegó a autenticarse después del cambio). Coincide con el bundle por
    // defecto, messages.properties.
    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("es");

    private final FirebaseMessaging firebaseMessaging;
    private final MessageSource messageSource;

    public NotificationService(FirebaseMessaging firebaseMessaging, MessageSource messageSource) {
        this.firebaseMessaging = firebaseMessaging;
        this.messageSource = messageSource;
    }

    // `language` es el del DESTINATARIO ('es'/'en', como lo guardan las apps
    // junto al fcmToken), no el de quien disparó la acción. Null/vacío ->
    // DEFAULT_LOCALE.
    //
    // No relanza: un push que falla no debe tumbar el flujo que lo dispara
    // (ej. una carrera ya aceptada no debe revertirse porque la notificación
    // no llegó). Solo se registra el error.
    public void sendPush(String token, String language, PushNotificationDTO payload) {
        if (token == null || token.isBlank()) {
            log.warn("NotificationDebug | Se omitió el envío del push '{}': token vacío", payload.titleKey());
            return;
        }

        String title;
        String body;
        try {
            Locale locale = resolveLocale(language);
            title = messageSource.getMessage(payload.titleKey(), null, locale);
            body = payload.bodyKey() != null
                    ? messageSource.getMessage(payload.bodyKey(), null, locale)
                    : payload.bodyText();
        } catch (NoSuchMessageException e) {
            // Key sin traducir: es un bug nuestro, no del usuario. Se omite el
            // push en vez de propagar -- mismo criterio que el catch de abajo.
            log.error("NotificationDebug | Falta la key del push en el catálogo: {}", e.getMessage(), e);
            return;
        }

        Message message = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .putData("title", title)
                .putData("subtitle", body)
                .putData("route", payload.route())
                // FCM exige valores no nulos en putData -- type/rideId son
                // los únicos campos opcionales del payload (ver PushNotificationDTO).
                .putData("type", payload.type() != null ? payload.type() : "")
                .putData("rideId", payload.rideId() != null ? payload.rideId() : "")
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
                                        .setTitle(title)
                                        .setBody(body)
                                        .build())
                                .setSound("default")
                                .build())
                        .build())
                .build();

        try {
            firebaseMessaging.send(message);
        } catch (FirebaseMessagingException e) {
            log.error("NotificationDebug | Error al enviar push '{}' a token={}: {}", payload.titleKey(), token, e.getMessage(), e);
        }
    }

    // Un idioma que no tenga bundle propio (o uno inválido, que
    // forLanguageTag devuelve como Locale vacío) termina igual en
    // messages.properties gracias a fallback-to-system-locale=false; este
    // chequeo solo evita el paso por MessageSource con un tag vacío.
    private static Locale resolveLocale(String language) {
        if (language == null || language.isBlank()) {
            return DEFAULT_LOCALE;
        }
        return Locale.forLanguageTag(language);
    }
}
