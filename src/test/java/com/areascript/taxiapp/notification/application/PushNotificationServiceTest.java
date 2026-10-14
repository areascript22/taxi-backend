package com.areascript.taxiapp.notification.application;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.notification.domain.Device;
import com.areascript.taxiapp.notification.domain.DeviceRegistry;
import com.areascript.taxiapp.notification.domain.PushContent;
import com.areascript.taxiapp.notification.domain.PushMessage;
import com.areascript.taxiapp.notification.domain.PushSender;
import com.areascript.taxiapp.notification.domain.Recipient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// Traducción y control de flujo. Con el puerto PushSender en el dominio esto
// ya es un test sin Firebase: el "sender" es una lista, y se puede afirmar
// sobre el TEXTO que se entrega (antes había que leer el Message del SDK por
// reflexión).
class PushNotificationServiceTest {

    private static final PushMessage RIDE_ACCEPTED = PushMessage.translated(
            "push.ride.accepted.title",
            "push.ride.accepted.body",
            "/ride_tracking"
    );

    private record Delivery(String deviceToken, PushContent content) {
    }

    private final List<Delivery> deliveries = new ArrayList<>();
    private final Map<Recipient, Device> devices = new HashMap<>();
    private RuntimeException registryFailure;
    private PushNotificationService service;

    @BeforeEach
    void setUp() {
        PushSender recordingSender = (deviceToken, content) -> deliveries.add(new Delivery(deviceToken, content));

        DeviceRegistry registry = recipient -> {
            if (registryFailure != null) {
                throw registryFailure;
            }
            return Optional.ofNullable(devices.get(recipient));
        };

        // Misma configuración que arma Spring Boot a partir de application.properties.
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);

        service = new PushNotificationService(recordingSender, messageSource, registry);
    }

    // ---- notifyUser: busca el dispositivo y el idioma del destinatario ----

    @Test
    void notifyUser_usa_el_token_y_el_idioma_del_destinatario() {
        devices.put(Recipient.passenger("p1"), new Device("fcm-p1", "en"));

        service.notifyUser(Recipient.passenger("p1"), RIDE_ACCEPTED);

        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).deviceToken()).isEqualTo("fcm-p1");
        assertThat(deliveries.get(0).content().title()).isEqualTo("Ride accepted!");
    }

    // El mismo uid puede existir como conductor y como pasajero.
    @Test
    void notifyUser_distingue_conductor_de_pasajero() {
        devices.put(Recipient.passenger("u1"), new Device("fcm-pasajero", "es"));
        devices.put(Recipient.driver("u1"), new Device("fcm-conductor", "es"));

        service.notifyUser(Recipient.driver("u1"), RIDE_ACCEPTED);

        assertThat(deliveries).extracting(Delivery::deviceToken).containsExactly("fcm-conductor");
    }

    @Test
    void notifyUser_sin_destinatario_no_envia_ni_relanza() {
        service.notifyUser(Recipient.driver("nadie"), RIDE_ACCEPTED);

        assertThat(deliveries).isEmpty();
    }

    @Test
    void notifyUser_con_destinatario_sin_token_no_envia() {
        devices.put(Recipient.driver("d1"), new Device(null, "es"));

        service.notifyUser(Recipient.driver("d1"), RIDE_ACCEPTED);

        assertThat(deliveries).isEmpty();
    }

    // Un fallo al buscar al destinatario no debe tumbar el flujo que notifica.
    @Test
    void notifyUser_si_falla_la_busqueda_no_relanza() {
        registryFailure = new ExternalServiceException("firestore caído", new RuntimeException());

        assertThatCode(() -> service.notifyUser(Recipient.driver("d1"), RIDE_ACCEPTED))
                .doesNotThrowAnyException();
        assertThat(deliveries).isEmpty();
    }

    // ---- send: ya con el token a mano ----

    @Test
    void entrega_el_push_en_el_idioma_del_destinatario() {
        service.send("token-123", "en", RIDE_ACCEPTED);

        assertThat(deliveries).hasSize(1);
        Delivery delivery = deliveries.get(0);
        assertThat(delivery.deviceToken()).isEqualTo("token-123");
        assertThat(delivery.content().title()).isEqualTo("Ride accepted!");
        assertThat(delivery.content().body()).isEqualTo("A driver is on the way to pick you up");
        assertThat(delivery.content().route()).isEqualTo("/ride_tracking");
        assertThat(delivery.content().type()).isNull();
        assertThat(delivery.content().rideId()).isNull();
    }

    @Test
    void en_espanol_usa_el_bundle_por_defecto() {
        service.send("token-123", "es", RIDE_ACCEPTED);

        assertThat(deliveries.get(0).content().title()).isEqualTo("¡Carrera aceptada!");
    }

    // Documentos creados antes de que existiera el campo `language`: el push
    // tiene que salir igual (en español), no omitirse.
    @Test
    void sin_idioma_guardado_o_con_un_idioma_desconocido_sale_en_espanol() {
        service.send("token-123", null, RIDE_ACCEPTED);
        service.send("token-123", "   ", RIDE_ACCEPTED);
        service.send("token-123", "pt", RIDE_ACCEPTED);

        assertThat(deliveries).hasSize(3);
        assertThat(deliveries).allSatisfy(delivery ->
                assertThat(delivery.content().title()).isEqualTo("¡Carrera aceptada!"));
    }

    @Test
    void omite_el_envio_cuando_no_hay_token() {
        service.send(null, "es", RIDE_ACCEPTED);
        service.send("", "es", RIDE_ACCEPTED);
        service.send("   ", "es", RIDE_ACCEPTED);

        assertThat(deliveries).isEmpty();
    }

    // Una key sin entrada en el catálogo es un bug nuestro, pero no puede
    // tumbar el flujo que disparó el push (una carrera ya aceptada no se
    // revierte porque falte una traducción).
    @Test
    void omite_el_envio_sin_relanzar_cuando_falta_la_key() {
        service.send(
                "token-123",
                "es",
                PushMessage.translated("push.inexistente.title", "push.inexistente.body", "/booking")
        );

        assertThat(deliveries).isEmpty();
    }

    // El body de un chat es texto del usuario: no pasa por el catálogo, así
    // que no puede faltar ninguna key ni romperse por las comillas simples
    // que MessageFormat interpretaría como escape.
    @Test
    void el_texto_del_usuario_viaja_intacto_y_solo_se_traduce_el_titulo() {
        service.send(
                "token-123",
                "en",
                PushMessage.withUserText(
                        "push.chat.fromDriver.title",
                        "Ya estoy aqui, 'en la esquina' {ahora}",
                        "/ride_tracking",
                        "chat_message",
                        "ride-1"
                )
        );

        PushContent content = deliveries.get(0).content();
        assertThat(content.title()).isEqualTo("Your driver messaged you");
        assertThat(content.body()).isEqualTo("Ya estoy aqui, 'en la esquina' {ahora}");
        assertThat(content.type()).isEqualTo("chat_message");
        assertThat(content.rideId()).isEqualTo("ride-1");
    }
}
