package com.areascript.taxiapp.characterization;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /api/rides/{passengerId}/complete: el conductor asignado finaliza.
class RideCompleteCharacterizationTest extends CharacterizationTestBase {

    @Test
    void el_conductor_asignado_finaliza_un_viaje_iniciado() throws Exception {
        givenRide("p1", "tripStarted", "d1");
        passengerProfile("p1", "fcm-p1", "es");

        mvc.perform(post("/api/rides/p1/complete").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("tripCompleted");
        // Hoy no se envía push al finalizar: el pasajero se entera por el
        // listener de Realtime DB.
        assertThat(sentPushes()).isEmpty();
    }

    @Test
    void otro_conductor_recibe_403() throws Exception {
        givenRide("p1", "tripStarted", "d1");

        mvc.perform(post("/api/rides/p1/complete").header("Authorization", userToken("d2")))
                .andExpect(status().isForbidden());

        assertThat(rideField("p1", "status")).isEqualTo("tripStarted");
    }

    @Test
    void el_pasajero_no_puede_finalizar() throws Exception {
        givenRide("p1", "tripStarted", "d1");

        mvc.perform(post("/api/rides/p1/complete").header("Authorization", userToken("p1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void finalizar_un_viaje_no_iniciado_responde_409() throws Exception {
        givenRide("p1", "driverArrived", "d1");

        mvc.perform(post("/api/rides/p1/complete").header("Authorization", userToken("d1")))
                .andExpect(status().isConflict());

        assertThat(rideField("p1", "status")).isEqualTo("driverArrived");
    }

    // El estado se valida antes que la identidad: un tercero sobre un viaje
    // no iniciado recibe 409, no 403.
    @Test
    void un_tercero_sobre_un_viaje_no_iniciado_recibe_409() throws Exception {
        givenRide("p1", "driverAssigned", "d1");

        mvc.perform(post("/api/rides/p1/complete").header("Authorization", userToken("intruso")))
                .andExpect(status().isConflict());
    }

    @Test
    void finalizar_dos_veces_responde_409() throws Exception {
        givenRide("p1", "tripCompleted", "d1");

        mvc.perform(post("/api/rides/p1/complete").header("Authorization", userToken("d1")))
                .andExpect(status().isConflict());
    }

    @Test
    void sin_solicitud_responde_404() throws Exception {
        mvc.perform(post("/api/rides/p1/complete").header("Authorization", userToken("d1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void sin_token_responde_401() throws Exception {
        givenRide("p1", "tripStarted", "d1");

        mvc.perform(post("/api/rides/p1/complete"))
                .andExpect(status().isUnauthorized());
    }
}
