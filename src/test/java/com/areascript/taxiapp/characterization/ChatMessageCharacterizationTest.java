package com.areascript.taxiapp.characterization;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.areascript.taxiapp.characterization.support.SentPush;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /api/rides/{passengerId}/messages: chat entre pasajero y conductor
// durante un viaje activo. El hilo es chats/{rideId}/messages.
class ChatMessageCharacterizationTest extends CharacterizationTestBase {

    private static final String RIDE_ID = "p1_1700000000000";
    private static final String MESSAGES = "chats/" + RIDE_ID + "/messages";

    private static String body(String text) {
        return "{\"text\": \"" + text + "\"}";
    }

    private Map<String, Object> onlyMessage() {
        List<String> ids = firestore.documentIds(MESSAGES);
        assertThat(ids).hasSize(1);
        return firestore.get(MESSAGES + "/" + ids.get(0));
    }

    @Test
    void el_pasajero_escribe_y_se_avisa_al_conductor() throws Exception {
        givenRide("p1", "driverAssigned", "d1");
        driverProfile("d1", null, "fcm-d1", "es");

        mvc.perform(post("/api/rides/p1/messages")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("  Estoy en la esquina  ")))
                .andExpect(status().isNoContent());

        Map<String, Object> message = onlyMessage();
        assertThat(message).containsEntry("senderId", "p1")
                .containsEntry("senderRole", "passenger")
                .containsEntry("text", "Estoy en la esquina");

        // Retención: expireAt = createdAt + 7 días (alimenta el TTL de Firestore).
        Timestamp createdAt = (Timestamp) message.get("createdAt");
        Timestamp expireAt = (Timestamp) message.get("expireAt");
        assertThat(expireAt.getSeconds() - createdAt.getSeconds()).isEqualTo(7L * 24 * 60 * 60);

        List<SentPush> pushes = sentPushes();
        assertThat(pushes).hasSize(1);
        SentPush push = pushes.get(0);
        assertThat(push.token()).isEqualTo("fcm-d1");
        assertThat(push.title()).isEqualTo("Tu pasajero te escribió");
        assertThat(push.body()).isEqualTo("Estoy en la esquina");
        assertThat(push.data()).containsEntry("route", "/booking")
                .containsEntry("type", "chat_message")
                .containsEntry("rideId", RIDE_ID);
    }

    @Test
    void el_conductor_escribe_y_se_avisa_al_pasajero() throws Exception {
        givenRide("p1", "tripStarted", "d1");
        passengerProfile("p1", "fcm-p1", "en");

        mvc.perform(post("/api/rides/p1/messages")
                        .header("Authorization", userToken("d1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Ya llegue")))
                .andExpect(status().isNoContent());

        assertThat(onlyMessage()).containsEntry("senderRole", "driver");
        SentPush push = sentPushes().get(0);
        assertThat(push.token()).isEqualTo("fcm-p1");
        assertThat(push.title()).isEqualTo("Your driver messaged you");
        assertThat(push.data()).containsEntry("route", "/ride_tracking");
    }

    // El texto del usuario viaja tal cual: sin pasar por MessageFormat.
    @Test
    void el_texto_con_comillas_y_llaves_llega_intacto_al_push() throws Exception {
        givenRide("p1", "driverAssigned", "d1");
        driverProfile("d1", null, "fcm-d1", "es");

        mvc.perform(post("/api/rides/p1/messages")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("'en la esquina' {ahora}")))
                .andExpect(status().isNoContent());

        assertThat(sentPushes().get(0).body()).isEqualTo("'en la esquina' {ahora}");
    }

    @Test
    void el_preview_del_push_se_corta_a_80_caracteres() throws Exception {
        givenRide("p1", "driverAssigned", "d1");
        driverProfile("d1", null, "fcm-d1", "es");
        String text = "a".repeat(100);

        mvc.perform(post("/api/rides/p1/messages")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(text)))
                .andExpect(status().isNoContent());

        assertThat(onlyMessage().get("text")).isEqualTo(text);
        assertThat(sentPushes().get(0).body()).isEqualTo("a".repeat(80) + "…");
    }

    @Test
    void el_mensaje_guardado_se_corta_a_1000_caracteres() throws Exception {
        givenRide("p1", "driverAssigned", "d1");

        mvc.perform(post("/api/rides/p1/messages")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("b".repeat(1500))))
                .andExpect(status().isNoContent());

        assertThat((String) onlyMessage().get("text")).hasSize(1000);
    }

    @Test
    void texto_vacio_o_ausente_responde_400() throws Exception {
        givenRide("p1", "driverAssigned", "d1");

        mvc.perform(post("/api/rides/p1/messages")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rides/p1/messages")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(firestore.documentIds(MESSAGES)).isEmpty();
    }

    @Test
    void un_tercero_recibe_403() throws Exception {
        givenRide("p1", "driverAssigned", "d1");

        mvc.perform(post("/api/rides/p1/messages")
                        .header("Authorization", userToken("intruso"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("hola")))
                .andExpect(status().isForbidden());

        assertThat(firestore.documentIds(MESSAGES)).isEmpty();
    }

    // Sin conductor asignado todavía no hay con quién chatear.
    @Test
    void con_la_solicitud_pending_responde_409() throws Exception {
        givenRide("p1", "pending", null);

        mvc.perform(post("/api/rides/p1/messages")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("hola")))
                .andExpect(status().isConflict());
    }

    @Test
    void con_el_viaje_terminado_responde_409() throws Exception {
        givenRide("p1", "tripCompleted", "d1");

        mvc.perform(post("/api/rides/p1/messages")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("hola")))
                .andExpect(status().isConflict());
    }

    @Test
    void sin_solicitud_responde_404() throws Exception {
        mvc.perform(post("/api/rides/p1/messages")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("hola")))
                .andExpect(status().isNotFound());
    }
}
