package com.areascript.taxiapp.characterization;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.areascript.taxiapp.characterization.support.FakeRealtimeDatabase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /api/rides/{passengerId}/arrived (el conductor llegó) y
// POST /api/rides/{passengerId}/start (el pasajero va en camino). Antes de la
// fase 9 las apps escribían estos estados directo en Realtime Database.
class RideProgressCharacterizationTest extends CharacterizationTestBase {

    // ---- arrived ----

    @Test
    void el_conductor_asignado_marca_que_llego() throws Exception {
        givenRide("p1", "driverAssigned", "d1");

        mvc.perform(post("/api/rides/p1/arrived").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("driverArrived");
        assertThat(rideField("p1", "updatedAt")).isEqualTo(FakeRealtimeDatabase.FIXED_SERVER_TIME);
        assertThat(rideField("p1", "driver/data/id")).isEqualTo("d1");
    }

    @Test
    void marcar_la_llegada_dos_veces_es_un_exito() throws Exception {
        givenRide("p1", "driverArrived", "d1");

        mvc.perform(post("/api/rides/p1/arrived").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("driverArrived");
    }

    @Test
    void otro_conductor_no_puede_marcar_la_llegada() throws Exception {
        givenRide("p1", "driverAssigned", "d1");

        mvc.perform(post("/api/rides/p1/arrived").header("Authorization", userToken("d2")))
                .andExpect(status().isForbidden());

        assertThat(rideField("p1", "status")).isEqualTo("driverAssigned");
    }

    @Test
    void el_pasajero_no_puede_marcar_la_llegada() throws Exception {
        givenRide("p1", "driverAssigned", "d1");

        mvc.perform(post("/api/rides/p1/arrived").header("Authorization", userToken("p1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void solo_se_marca_desde_driverAssigned() throws Exception {
        givenRide("p1", "pending", null);
        mvc.perform(post("/api/rides/p1/arrived").header("Authorization", userToken("d1")))
                .andExpect(status().isConflict());

        givenRide("p2", "tripStarted", "d1");
        mvc.perform(post("/api/rides/p2/arrived").header("Authorization", userToken("d1")))
                .andExpect(status().isConflict());
        assertThat(rideField("p2", "status")).isEqualTo("tripStarted");
    }

    @Test
    void marcar_la_llegada_sin_carrera_responde_404() throws Exception {
        mvc.perform(post("/api/rides/p1/arrived").header("Authorization", userToken("d1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void marcar_la_llegada_sin_token_responde_401() throws Exception {
        givenRide("p1", "driverAssigned", "d1");

        mvc.perform(post("/api/rides/p1/arrived"))
                .andExpect(status().isUnauthorized());

        assertThat(rideField("p1", "status")).isEqualTo("driverAssigned");
    }

    // ---- start ----

    @Test
    void el_pasajero_confirma_y_el_viaje_empieza() throws Exception {
        givenRide("p1", "driverArrived", "d1");

        mvc.perform(post("/api/rides/p1/start").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("tripStarted");
        assertThat(rideField("p1", "updatedAt")).isEqualTo(FakeRealtimeDatabase.FIXED_SERVER_TIME);
    }

    @Test
    void iniciar_dos_veces_es_un_exito() throws Exception {
        givenRide("p1", "tripStarted", "d1");

        mvc.perform(post("/api/rides/p1/start").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("tripStarted");
    }

    // El conductor no puede confirmar "en camino" por el pasajero.
    @Test
    void el_conductor_no_puede_iniciar_el_viaje() throws Exception {
        givenRide("p1", "driverArrived", "d1");

        mvc.perform(post("/api/rides/p1/start").header("Authorization", userToken("d1")))
                .andExpect(status().isForbidden());

        assertThat(rideField("p1", "status")).isEqualTo("driverArrived");
    }

    // Cada pasajero solo puede iniciar SU viaje.
    @Test
    void un_pasajero_no_puede_iniciar_el_viaje_de_otro() throws Exception {
        givenRide("p1", "driverArrived", "d1");

        mvc.perform(post("/api/rides/p1/start").header("Authorization", userToken("p2")))
                .andExpect(status().isForbidden());

        assertThat(rideField("p1", "status")).isEqualTo("driverArrived");
    }

    @Test
    void no_se_inicia_hasta_que_el_conductor_llego() throws Exception {
        givenRide("p1", "driverAssigned", "d1");

        mvc.perform(post("/api/rides/p1/start").header("Authorization", userToken("p1")))
                .andExpect(status().isConflict());

        assertThat(rideField("p1", "status")).isEqualTo("driverAssigned");
    }

    @Test
    void iniciar_sin_carrera_responde_404() throws Exception {
        mvc.perform(post("/api/rides/p1/start").header("Authorization", userToken("p1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void iniciar_sin_token_responde_401() throws Exception {
        givenRide("p1", "driverArrived", "d1");

        mvc.perform(post("/api/rides/p1/start"))
                .andExpect(status().isUnauthorized());
    }

    // El recorrido completo, solo por la API.
    @Test
    void el_ciclo_completo_pasa_por_el_servidor() throws Exception {
        givenRide("p1", "pending", null);
        driverProfile("d1", null, "fcm-d1", "es");

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", userToken("d1"))
                        .contentType("application/json")
                        .content("{\"latitude\": -1.67, \"longitude\": -78.65}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/rides/p1/arrived").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/rides/p1/start").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/rides/p1/complete").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("tripCompleted");
    }
}
