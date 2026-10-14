package com.areascript.taxiapp.notification.infra;

import com.areascript.taxiapp.notification.domain.PushContent;
import com.google.firebase.ErrorCode;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// El mensaje FCM que se arma: payload de datos que lee la app, y la
// configuración de prioridad/canal sin la cual Android demora o silencia el
// push. La clase Message del SDK no expone getters: se lee por reflexión.
class FcmPushSenderTest {

    private FirebaseMessaging firebaseMessaging;
    private FcmPushSender sender;

    @BeforeEach
    void setUp() {
        firebaseMessaging = mock(FirebaseMessaging.class);
        sender = new FcmPushSender(firebaseMessaging);
    }

    private Message sentMessage() throws Exception {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(firebaseMessaging).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void arma_el_payload_de_datos_que_lee_la_app() throws Exception {
        sender.send("fcm-token", new PushContent("Título", "Cuerpo", "/ride_tracking", "chat_message", "ride-1"));

        Message message = sentMessage();
        assertThat(read(message, "token")).isEqualTo("fcm-token");
        assertThat(data(message)).containsEntry("title", "Título")
                .containsEntry("subtitle", "Cuerpo")
                .containsEntry("route", "/ride_tracking")
                .containsEntry("type", "chat_message")
                .containsEntry("rideId", "ride-1");
        Object notification = read(message, "notification");
        assertThat(read(notification, "title")).isEqualTo("Título");
        assertThat(read(notification, "body")).isEqualTo("Cuerpo");
    }

    // FCM no admite valores nulos en el mapa de datos.
    @Test
    void type_y_rideId_ausentes_viajan_como_texto_vacio() throws Exception {
        sender.send("fcm-token", new PushContent("T", "B", "/booking", null, null));

        assertThat(data(sentMessage())).containsEntry("type", "").containsEntry("rideId", "");
    }

    @Test
    void usa_prioridad_alta_y_el_canal_de_las_apps() throws Exception {
        sender.send("fcm-token", new PushContent("T", "B", "/booking", null, null));

        Object androidConfig = read(sentMessage(), "androidConfig");
        assertThat(read(androidConfig, "priority")).isEqualTo("high");
        Object androidNotification = read(androidConfig, "notification");
        assertThat(read(androidNotification, "channelId")).isEqualTo("high_importance_channel");
        assertThat(read(androidNotification, "priority").toString()).containsIgnoringCase("HIGH");
    }

    @Test
    void pide_entrega_inmediata_en_ios() throws Exception {
        sender.send("fcm-token", new PushContent("T", "B", "/booking", null, null));

        Object apnsConfig = read(sentMessage(), "apnsConfig");
        assertThat(read(apnsConfig, "headers")).isEqualTo(Map.of("apns-priority", "10"));
    }

    // Contrato de PushSender: un fallo de FCM se registra y no se propaga.
    @Test
    void no_relanza_si_fcm_rechaza_el_mensaje() throws Exception {
        FirebaseMessagingException failure = mock(FirebaseMessagingException.class);
        when(failure.getMessage()).thenReturn("simulado");
        when(failure.getErrorCode()).thenReturn(ErrorCode.UNAVAILABLE);
        doThrow(failure).when(firebaseMessaging).send(any(Message.class));

        assertThatCode(() -> sender.send("fcm-token", new PushContent("T", "B", "/booking", null, null)))
                .doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> data(Message message) throws Exception {
        return (Map<String, String>) read(message, "data");
    }

    private static Object read(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
