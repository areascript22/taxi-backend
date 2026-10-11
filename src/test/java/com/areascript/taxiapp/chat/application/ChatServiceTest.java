package com.areascript.taxiapp.chat.application;

import com.areascript.taxiapp.chat.domain.ChatMessage;
import com.areascript.taxiapp.chat.domain.ChatRepository;
import com.areascript.taxiapp.chat.domain.ChatRide;
import com.areascript.taxiapp.chat.domain.ChatRideLookup;
import com.areascript.taxiapp.chat.domain.SenderRole;
import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.common.error.ConflictException;
import com.areascript.taxiapp.common.error.ForbiddenException;
import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.notification.application.PushNotificationService;
import com.areascript.taxiapp.notification.domain.Device;
import com.areascript.taxiapp.notification.domain.PushContent;
import com.areascript.taxiapp.notification.domain.Recipient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Las reglas del chat sin Firebase: puertos en memoria y un reloj fijo.
class ChatServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private record Delivery(String deviceToken, PushContent content) {
    }

    private final Map<String, ChatRide> ridesByPassenger = new java.util.HashMap<>();
    private final List<ChatMessage> savedMessages = new ArrayList<>();
    private final List<Delivery> deliveries = new ArrayList<>();
    private ChatService service;

    @BeforeEach
    void setUp() {
        ChatRideLookup lookup = passengerId -> Optional.ofNullable(ridesByPassenger.get(passengerId));

        ChatRepository repository = new ChatRepository() {
            @Override
            public void createThread(String rideId, String passengerId, String driverId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void addMessage(ChatMessage message) {
                savedMessages.add(message);
            }
        };

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);

        Map<Recipient, Device> devices = Map.of(
                Recipient.passenger("p1"), new Device("fcm-p1", "en"),
                Recipient.driver("d1"), new Device("fcm-d1", "es"));
        PushNotificationService push = new PushNotificationService(
                (token, content) -> deliveries.add(new Delivery(token, content)),
                messageSource,
                recipient -> Optional.ofNullable(devices.get(recipient)));

        TaxiappProperties properties = new TaxiappProperties(
                new TaxiappProperties.Rides(Duration.ofSeconds(35), Duration.ofSeconds(10)),
                new TaxiappProperties.Chat(20, 10, Duration.ofDays(7)),
                null);

        service = new ChatService(
                lookup, repository, push, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        ridesByPassenger.put("p1", new ChatRide("ride-1", "p1", "d1", true));
    }

    @Test
    void el_pasajero_escribe_y_se_avisa_al_conductor() {
        service.sendMessage("p1", "p1", "  Estoy afuera  ");

        assertThat(savedMessages).hasSize(1);
        ChatMessage message = savedMessages.get(0);
        assertThat(message.rideId()).isEqualTo("ride-1");
        assertThat(message.senderId()).isEqualTo("p1");
        assertThat(message.senderRole()).isEqualTo(SenderRole.PASSENGER);
        assertThat(message.text()).isEqualTo("Estoy afuera");

        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).deviceToken()).isEqualTo("fcm-d1");
        assertThat(deliveries.get(0).content().title()).isEqualTo("Tu pasajero te escribió");
        assertThat(deliveries.get(0).content().route()).isEqualTo("/booking");
        assertThat(deliveries.get(0).content().type()).isEqualTo("chat_message");
        assertThat(deliveries.get(0).content().rideId()).isEqualTo("ride-1");
    }

    @Test
    void el_conductor_escribe_y_se_avisa_al_pasajero_en_su_idioma() {
        service.sendMessage("p1", "d1", "Ya llegué");

        assertThat(savedMessages.get(0).senderRole()).isEqualTo(SenderRole.DRIVER);
        assertThat(deliveries.get(0).deviceToken()).isEqualTo("fcm-p1");
        assertThat(deliveries.get(0).content().title()).isEqualTo("Your driver messaged you");
        assertThat(deliveries.get(0).content().route()).isEqualTo("/ride_tracking");
    }

    // La retención sale de la configuración y del reloj, no del sistema.
    @Test
    void el_mensaje_expira_a_los_7_dias_de_haberse_enviado() {
        service.sendMessage("p1", "p1", "hola");

        ChatMessage message = savedMessages.get(0);
        assertThat(message.createdAt()).isEqualTo(NOW);
        assertThat(message.expireAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void el_mensaje_se_corta_al_maximo_y_el_preview_del_push_es_mas_corto() {
        service.sendMessage("p1", "p1", "a".repeat(50));

        assertThat(savedMessages.get(0).text()).isEqualTo("a".repeat(20));
        assertThat(deliveries.get(0).content().body()).isEqualTo("a".repeat(10) + "…");
    }

    @Test
    void un_texto_dentro_del_preview_viaja_completo() {
        service.sendMessage("p1", "p1", "corto");

        assertThat(deliveries.get(0).content().body()).isEqualTo("corto");
    }

    @Test
    void un_texto_vacio_o_nulo_se_rechaza_antes_de_consultar_nada() {
        ridesByPassenger.clear();

        assertThatThrownBy(() -> service.sendMessage("p1", "p1", "   "))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.sendMessage("p1", "p1", null))
                .isInstanceOf(InvalidInputException.class);
        assertThat(savedMessages).isEmpty();
    }

    @Test
    void sin_carrera_es_404() {
        assertThatThrownBy(() -> service.sendMessage("nadie", "nadie", "hola"))
                .isInstanceOf(NotFoundException.class);
    }

    // Sin conductor asignado, o con el viaje terminado, no se puede escribir.
    @Test
    void una_carrera_no_activa_es_409() {
        ridesByPassenger.put("p2", new ChatRide("ride-2", "p2", null, false));

        assertThatThrownBy(() -> service.sendMessage("p2", "p2", "hola"))
                .isInstanceOf(ConflictException.class);
        assertThat(savedMessages).isEmpty();
    }

    @Test
    void un_tercero_es_403_y_no_guarda_nada() {
        assertThatThrownBy(() -> service.sendMessage("p1", "intruso", "hola"))
                .isInstanceOf(ForbiddenException.class);

        assertThat(savedMessages).isEmpty();
        assertThat(deliveries).isEmpty();
    }

    // Un push que no llega no revierte un mensaje ya guardado.
    @Test
    void sin_dispositivo_del_destinatario_el_mensaje_se_guarda_igual() {
        ridesByPassenger.put("p9", new ChatRide("ride-9", "p9", "d9", true));

        service.sendMessage("p9", "p9", "hola");

        assertThat(savedMessages).hasSize(1);
        assertThat(deliveries).isEmpty();
    }
}
