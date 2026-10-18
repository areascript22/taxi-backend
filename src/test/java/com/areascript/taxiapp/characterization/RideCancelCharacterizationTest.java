package com.areascript.taxiapp.characterization;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.areascript.taxiapp.characterization.support.SentPush;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /api/rides/{passengerId}/cancel: cancela el pasajero o el conductor
// asignado. El rol se deduce del uid del token.
class RideCancelCharacterizationTest extends CharacterizationTestBase {

    @Test
    void el_pasajero_cancela_su_solicitud_pending_sin_avisar_a_nadie() throws Exception {
        givenRide("p1", "pending", null);

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("cancelled");
        assertThat(rideField("p1", "cancelledBy")).isEqualTo("passenger");
        assertThat(sentPushes()).isEmpty();
    }

    @Test
    void el_pasajero_cancela_con_conductor_asignado_y_se_avisa_al_conductor() throws Exception {
        givenRide("p1", "driverArrived", "d1");
        driverProfile("d1", null, "fcm-d1", "en");

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "cancelledBy")).isEqualTo("passenger");
        List<SentPush> pushes = sentPushes();
        assertThat(pushes).hasSize(1);
        assertThat(pushes.get(0).token()).isEqualTo("fcm-d1");
        assertThat(pushes.get(0).title()).isEqualTo("Ride cancelled");
        assertThat(pushes.get(0).data()).containsEntry("route", "/booking");
    }

    @Test
    void el_conductor_asignado_cancela_y_se_avisa_al_pasajero() throws Exception {
        givenRide("p1", "tripStarted", "d1");
        passengerProfile("p1", "fcm-p1", "es");

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("cancelled");
        assertThat(rideField("p1", "cancelledBy")).isEqualTo("driver");
        List<SentPush> pushes = sentPushes();
        assertThat(pushes).hasSize(1);
        assertThat(pushes.get(0).token()).isEqualTo("fcm-p1");
        assertThat(pushes.get(0).title()).isEqualTo("Viaje cancelado");
        assertThat(pushes.get(0).body()).isEqualTo("Tu conductor canceló el viaje");
        assertThat(pushes.get(0).data()).containsEntry("route", "/ride_tracking");
    }

    @Test
    void un_tercero_recibe_403_y_la_carrera_no_cambia() throws Exception {
        givenRide("p1", "driverAssigned", "d1");

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("intruso")))
                .andExpect(status().isForbidden());

        assertThat(rideField("p1", "status")).isEqualTo("driverAssigned");
        assertThat(sentPushes()).isEmpty();
    }

    // Idempotente: "ya cancelada" es el estado deseado.
    @Test
    void cancelar_una_carrera_ya_cancelada_responde_204_sin_avisar() throws Exception {
        givenRide("p1", "cancelled", "d1");
        driverProfile("d1", null, "fcm-d1", "es");

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());

        assertThat(sentPushes()).isEmpty();
    }

    @Test
    void cancelar_sin_solicitud_responde_204() throws Exception {
        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());
    }

    @Test
    void cancelar_un_viaje_completado_responde_409() throws Exception {
        givenRide("p1", "tripCompleted", "d1");

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("p1")))
                .andExpect(status().isConflict());

        assertThat(rideField("p1", "status")).isEqualTo("tripCompleted");
    }

    @Test
    void sin_token_responde_401() throws Exception {
        givenRide("p1", "pending", null);

        mvc.perform(post("/api/rides/p1/cancel"))
                .andExpect(status().isUnauthorized());

        assertThat(rideField("p1", "status")).isEqualTo("pending");
    }

    // Unos 10 s después de un estado terminal el servidor borra el nodo.
    @Test
    void el_nodo_se_borra_unos_segundos_despues_de_cancelar() throws Exception {
        givenRide("p1", "pending", null);

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());
        assertThat(rtdb.get(RIDES + "/p1")).isNotNull();

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> rtdb.get(RIDES + "/p1") == null);
    }

    // La limpieza solo borra si el nodo sigue en un estado terminal: no pisa
    // una solicitud nueva creada en la misma key mientras tanto.
    @Test
    void la_limpieza_no_borra_una_solicitud_nueva_del_mismo_pasajero() throws Exception {
        givenRide("p1", "pending", null);

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());
        givenRide("p1", "pending", null);

        Thread.sleep(Duration.ofSeconds(12));

        assertThat(rideField("p1", "status")).isEqualTo("pending");
    }
}
