package com.areascript.taxiapp.service;

import com.areascript.taxiapp.dto.PushNotificationDTO;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// Qué se verifica acá: el control de flujo (cuándo se envía y cuándo no, y
// que nada de esto relance). El TEXTO renderizado se verifica en
// PushMessageCatalogTest, contra el MessageSource: la clase Message de
// firebase-admin no expone getters, así que afirmar sobre el título a través
// del objeto enviado obligaría a leer campos por reflexión.
class NotificationServiceTest {

    private static final PushNotificationDTO RIDE_ACCEPTED = PushNotificationDTO.translated(
            "push.ride.accepted.title",
            "push.ride.accepted.body",
            "/ride_tracking"
    );

    private FirebaseMessaging firebaseMessaging;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        firebaseMessaging = mock(FirebaseMessaging.class);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);

        service = new NotificationService(firebaseMessaging, messageSource);
    }

    @Test
    void envia_el_push_con_el_idioma_del_destinatario() throws Exception {
        service.sendPush("token-123", "en", RIDE_ACCEPTED);

        verify(firebaseMessaging, times(1)).send(any(Message.class));
    }

    // Documentos creados antes de que existiera el campo `language`: el push
    // tiene que salir igual (en español), no omitirse.
    @Test
    void envia_el_push_cuando_el_destinatario_no_tiene_idioma_guardado() throws Exception {
        service.sendPush("token-123", null, RIDE_ACCEPTED);
        service.sendPush("token-123", "   ", RIDE_ACCEPTED);
        service.sendPush("token-123", "pt", RIDE_ACCEPTED);

        verify(firebaseMessaging, times(3)).send(any(Message.class));
    }

    @Test
    void omite_el_envio_cuando_no_hay_token() {
        service.sendPush(null, "es", RIDE_ACCEPTED);
        service.sendPush("", "es", RIDE_ACCEPTED);
        service.sendPush("   ", "es", RIDE_ACCEPTED);

        verifyNoInteractions(firebaseMessaging);
    }

    // Una key sin entrada en el catálogo es un bug nuestro, pero no puede
    // tumbar el flujo que disparó el push (una carrera ya aceptada no se
    // revierte porque falte una traducción).
    @Test
    void omite_el_envio_sin_relanzar_cuando_falta_la_key() throws Exception {
        service.sendPush(
                "token-123",
                "es",
                PushNotificationDTO.translated("push.inexistente.title", "push.inexistente.body", "/booking")
        );

        verify(firebaseMessaging, never()).send(any(Message.class));
    }

    // El body de un chat es texto del usuario: no pasa por el catálogo, así
    // que no puede faltar ninguna key ni romperse por las comillas simples
    // que MessageFormat interpretaría como escape.
    @Test
    void envia_el_push_de_chat_con_el_texto_del_usuario_sin_traducir() throws Exception {
        service.sendPush(
                "token-123",
                "en",
                PushNotificationDTO.withUserText(
                        "push.chat.fromDriver.title",
                        "Ya estoy aqui, 'en la esquina' {ahora}",
                        "/ride_tracking",
                        "chat_message",
                        "ride-1"
                )
        );

        verify(firebaseMessaging, times(1)).send(any(Message.class));
    }
}
