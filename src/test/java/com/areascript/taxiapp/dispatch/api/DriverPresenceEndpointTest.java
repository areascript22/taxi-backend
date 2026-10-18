package com.areascript.taxiapp.dispatch.api;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// El interruptor "en línea", la ubicación del conductor y cómo una carrera lo
// marca ocupado, a nivel HTTP. Filtro de seguridad, controllers y casos de uso
// son los reales; solo el almacenamiento (Firebase y la base) es de mentira.
class DriverPresenceEndpointTest extends CharacterizationTestBase {

    private static final String JSON = "application/json";

    // Un conductor habilitado: aprobado y no bloqueado en Firestore (donde hoy
    // vive esa verdad) y con perfil en la base.
    @BeforeEach
    void approvedDriver() {
        firestore.put("drivers/d1", Map.of("firstName", "Uno", "approvalStatus", "approved"));
        presence.register("d1");
    }

    private static String availability(boolean online) {
        return "{\"online\":" + online + "}";
    }

    private static String location(double lat, double lng) {
        return "{\"latitude\":" + lat + ",\"longitude\":" + lng + "}";
    }

    // ---- Ponerse en línea ----

    @Test
    void un_conductor_aprobado_puede_ponerse_en_linea() throws Exception {
        mvc.perform(put("/api/me/driver/availability").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(availability(true)))
                .andExpect(status().isNoContent());

        assertThat(presence.isOnline("d1")).isTrue();
    }

    @Test
    void un_conductor_pendiente_de_aprobacion_no_puede() throws Exception {
        firestore.put("drivers/d1", Map.of("firstName", "Uno", "approvalStatus", "pending"));

        mvc.perform(put("/api/me/driver/availability").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(availability(true)))
                .andExpect(status().isForbidden());

        assertThat(presence.isOnline("d1")).isFalse();
    }

    @Test
    void un_conductor_bloqueado_no_puede() throws Exception {
        firestore.put("drivers/d1", Map.of("firstName", "Uno", "approvalStatus", "approved", "isBlocked", true));

        mvc.perform(put("/api/me/driver/availability").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(availability(true)))
                .andExpect(status().isForbidden());
    }

    @Test
    void quien_no_es_conductor_no_puede() throws Exception {
        mvc.perform(put("/api/me/driver/availability").header("Authorization", userToken("pasajero"))
                        .contentType(JSON).content(availability(true)))
                .andExpect(status().isForbidden());
    }

    // Aprobado en Firestore pero su perfil todavía no está en la base.
    @Test
    void sin_perfil_en_la_base_responde_404() throws Exception {
        presence.clear();

        mvc.perform(put("/api/me/driver/availability").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(availability(true)))
                .andExpect(status().isNotFound());
    }

    @Test
    void desconectarse_siempre_se_acepta_y_borra_la_ubicacion() throws Exception {
        presence.setOnline("d1", true);
        presence.recordLocationIfAvailable("d1", -1.66, -78.65);
        // bloqueado: aun asi puede dejar de recibir pedidos
        firestore.put("drivers/d1", Map.of("firstName", "Uno", "isBlocked", true));

        mvc.perform(put("/api/me/driver/availability").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(availability(false)))
                .andExpect(status().isNoContent());

        assertThat(presence.isOnline("d1")).isFalse();
        assertThat(presence.locationOf("d1")).as("no queda rastro").isNull();
    }

    @Test
    void falta_el_campo_online_es_un_400() throws Exception {
        mvc.perform(put("/api/me/driver/availability").header("Authorization", userToken("d1"))
                        .contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sin_autenticar_responde_401() throws Exception {
        mvc.perform(put("/api/me/driver/availability").contentType(JSON).content(availability(true)))
                .andExpect(status().isUnauthorized());
    }

    // Una marca "en carrera" que quedó colgada no impide volver a trabajar.
    @Test
    void ponerse_en_linea_corrige_una_marca_de_carrera_colgada() throws Exception {
        presence.setOnRide("d1", true);                 // pero no tiene ninguna carrera

        mvc.perform(put("/api/me/driver/availability").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(availability(true)))
                .andExpect(status().isNoContent());

        assertThat(presence.isOnRide("d1")).isFalse();
    }

    // ---- Ubicación sin carrera: va a la base ----

    @Test
    void en_linea_y_libre_la_ubicacion_se_guarda() throws Exception {
        presence.setOnline("d1", true);

        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(location(-1.6635, -78.6546)))
                .andExpect(status().isNoContent());

        assertThat(presence.locationOf("d1").latitude()).isEqualTo(-1.6635);
        assertThat(presence.locationOf("d1").longitude()).isEqualTo(-78.6546);
    }

    @Test
    void fuera_de_linea_responde_409_y_no_guarda_nada() throws Exception {
        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(location(-1.6635, -78.6546)))
                .andExpect(status().isConflict());

        assertThat(presence.locationOf("d1")).isNull();
    }

    @Test
    void sin_perfil_de_conductor_responde_404() throws Exception {
        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("nadie"))
                        .contentType(JSON).content(location(-1.6635, -78.6546)))
                .andExpect(status().isNotFound());
    }

    @Test
    void una_ubicacion_fuera_de_rango_es_un_400() throws Exception {
        presence.setOnline("d1", true);

        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(location(95.0, -78.6546)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(location(-1.66, 190.0)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("d1"))
                        .contentType(JSON).content("{\"latitude\":-1.66}"))
                .andExpect(status().isBadRequest());
    }

    // ---- Ubicación en carrera: va al nodo de la carrera, no a la base ----

    @Test
    void en_carrera_la_ubicacion_va_al_nodo_de_la_carrera_y_no_a_la_base() throws Exception {
        givenRide("p1", "driverAssigned", "d1");
        presence.setOnline("d1", true);
        presence.setOnRide("d1", true);

        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("d1"))
                        .contentType(JSON)
                        .content("{\"latitude\":-1.67,\"longitude\":-78.66,\"passengerId\":\"p1\"}"))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "driver/location/latitude")).isEqualTo(-1.67);
        assertThat(rideField("p1", "driver/location/longitude")).isEqualTo(-78.66);
        assertThat(rideField("p1", "driver/location/updatedAt")).isNotNull();
        assertThat(presence.locationOf("d1")).as("mientras esta ocupado no se escribe en la base").isNull();
        // Y el estado de la carrera no se toca.
        assertThat(rideField("p1", "status")).isEqualTo("driverAssigned");
    }

    @Test
    void otro_conductor_no_puede_publicar_en_la_carrera_ajena() throws Exception {
        givenRide("p1", "driverAssigned", "otro");

        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("d1"))
                        .contentType(JSON)
                        .content("{\"latitude\":-1.99,\"longitude\":-78.99,\"passengerId\":\"p1\"}"))
                .andExpect(status().isForbidden());

        // La ubicacion que ya tenia la carrera no se pisa.
        assertThat(rideField("p1", "driver/location/latitude")).isNotEqualTo(-1.99);
    }

    @Test
    void una_carrera_que_no_existe_responde_404_y_no_crea_el_nodo() throws Exception {
        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("d1"))
                        .contentType(JSON)
                        .content("{\"latitude\":-1.67,\"longitude\":-78.66,\"passengerId\":\"fantasma\"}"))
                .andExpect(status().isNotFound());

        // Lo importante: no quedó un nodo taxi_requests/fantasma que los
        // conductores verían como una solicitud.
        assertThat(rtdb.get(RIDES + "/fantasma")).isNull();
    }

    @Test
    void una_carrera_terminada_responde_409() throws Exception {
        givenRide("p1", "tripCompleted", "d1");

        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("d1"))
                        .contentType(JSON)
                        .content("{\"latitude\":-1.67,\"longitude\":-78.66,\"passengerId\":\"p1\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void en_carrera_sin_decir_cual_responde_409_pero_una_marca_colgada_se_corrige() throws Exception {
        presence.setOnline("d1", true);
        presence.setOnRide("d1", true);
        givenRide("p1", "driverAssigned", "d1");        // de verdad va en una

        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(location(-1.67, -78.66)))
                .andExpect(status().isConflict());

        // La carrera terminó pero el marcador quedó colgado: se corrige y guarda.
        rtdb.put(RIDES + "/p1", null);
        mvc.perform(put("/api/me/driver/location").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(location(-1.67, -78.66)))
                .andExpect(status().isNoContent());

        assertThat(presence.isOnRide("d1")).isFalse();
        assertThat(presence.locationOf("d1")).isNotNull();
    }

    // ---- El servidor marca ocupado y libre solo ----

    @Test
    void aceptar_una_carrera_marca_al_conductor_ocupado() throws Exception {
        givenRide("p1", "pending", null);
        presence.setOnline("d1", true);

        mvc.perform(post("/api/rides/p1/accept").header("Authorization", userToken("d1"))
                        .contentType(JSON).content("{\"latitude\":-1.66,\"longitude\":-78.65}"))
                .andExpect(status().isNoContent());

        assertThat(presence.isOnRide("d1")).isTrue();
    }

    @Test
    void cancelar_o_finalizar_la_carrera_lo_libera() throws Exception {
        // cancelar
        givenRide("p1", "driverAssigned", "d1");
        presence.setOnRide("d1", true);

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());
        assertThat(presence.isOnRide("d1")).isFalse();

        // finalizar
        givenRide("p2", "tripStarted", "d1");
        presence.setOnRide("d1", true);

        mvc.perform(post("/api/rides/p2/complete").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());
        assertThat(presence.isOnRide("d1")).isFalse();
    }

    @Test
    void que_un_pasajero_cancele_una_solicitud_sin_conductor_no_toca_a_nadie() throws Exception {
        givenRide("p1", "pending", null);
        presence.setOnRide("d1", true);                 // ocupado en OTRA carrera

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());

        assertThat(presence.isOnRide("d1")).as("sin conductor no hay a quien liberar").isTrue();
    }
}
