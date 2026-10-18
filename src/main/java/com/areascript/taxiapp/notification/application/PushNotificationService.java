package com.areascript.taxiapp.notification.application;

import com.areascript.taxiapp.notification.domain.Device;
import com.areascript.taxiapp.notification.domain.DeviceRegistry;
import com.areascript.taxiapp.notification.domain.PushContent;
import com.areascript.taxiapp.notification.domain.PushMessage;
import com.areascript.taxiapp.notification.domain.PushSender;
import com.areascript.taxiapp.notification.domain.Recipient;
import com.areascript.taxiapp.notification.domain.UserNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Service;

import java.util.Locale;

// Caso de uso "avisar a un usuario": traduce el PushMessage al idioma del
// destinatario y se lo pasa al PushSender. No conoce Firebase.
@Service
public class PushNotificationService implements UserNotifier {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    // Idioma del push cuando el destinatario no tiene `language` guardado
    // (documento creado antes de que existiera el campo, o usuario que nunca
    // llegó a autenticarse después del cambio). Coincide con el bundle por
    // defecto, messages.properties.
    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("es");

    private final PushSender pushSender;
    private final MessageSource messageSource;
    private final DeviceRegistry deviceRegistry;

    public PushNotificationService(
            PushSender pushSender,
            MessageSource messageSource,
            DeviceRegistry deviceRegistry
    ) {
        this.pushSender = pushSender;
        this.messageSource = messageSource;
        this.deviceRegistry = deviceRegistry;
    }

    // Avisa a un usuario: busca su dispositivo e idioma y le entrega el push.
    // Es el puerto que usan las demás features (UserNotifier); send(...) queda
    // para quien ya tiene el token a mano.
    //
    // No relanza, igual que send: si no se encuentra al usuario o falla la
    // búsqueda, se registra y se sigue (una carrera ya aceptada no se revierte
    // porque la notificación no llegó).
    @Override
    public void notifyUser(Recipient recipient, PushMessage message) {
        Device device;
        try {
            device = deviceRegistry.findDevice(recipient).orElse(null);
        } catch (RuntimeException e) {
            log.error("NotificationDebug | No se pudo notificar a {}/{}: {}", recipient.kind(), recipient.uid(), e.getMessage(), e);
            return;
        }

        if (device == null) {
            log.warn("NotificationDebug | No se encontró el destinatario {}/{} para notificarle", recipient.kind(), recipient.uid());
            return;
        }

        send(device.deviceToken(), device.language(), message);
    }

    // `language` es el del DESTINATARIO ('es'/'en', como lo guardan las apps
    // junto al fcmToken), no el de quien disparó la acción. Null/vacío ->
    // DEFAULT_LOCALE.
    //
    // No relanza: un push que falla no debe tumbar el flujo que lo dispara
    // (ej. una carrera ya aceptada no debe revertirse porque la notificación
    // no llegó). Solo se registra el error.
    public void send(String deviceToken, String language, PushMessage message) {
        if (deviceToken == null || deviceToken.isBlank()) {
            log.warn("NotificationDebug | Se omitió el envío del push '{}': token vacío", message.titleKey());
            return;
        }

        PushContent content;
        try {
            Locale locale = resolveLocale(language);
            String title = messageSource.getMessage(message.titleKey(), null, locale);
            String body = message.bodyKey() != null
                    ? messageSource.getMessage(message.bodyKey(), null, locale)
                    : message.bodyText();
            content = new PushContent(title, body, message.route(), message.type(), message.rideId());
        } catch (NoSuchMessageException e) {
            // Key sin traducir: es un bug nuestro, no del usuario. Se omite el
            // push en vez de propagar -- mismo criterio que el contrato de
            // PushSender.
            log.error("NotificationDebug | Falta la key del push en el catálogo: {}", e.getMessage(), e);
            return;
        }

        pushSender.send(deviceToken, content);
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
