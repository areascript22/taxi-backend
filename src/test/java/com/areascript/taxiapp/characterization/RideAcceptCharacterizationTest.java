package com.areascript.taxiapp.characterization;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.areascript.taxiapp.characterization.support.FakeRealtimeDatabase;
import com.areascript.taxiapp.characterization.support.SentPush;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /api/rides/{passengerId}/accept: un conductor acepta una solicitud.
class RideAcceptCharacterizationTest extends CharacterizationTestBase {

    private static final String DRIVER_LOCATION = "{\"latitude\": -1.67, \"longitude\": -78.65}";

    private String driverToken() throws Exception {
        return token("d1", "d1@test.com", "Carlos Conductor", "https://foto/d1.jpg", Map.of());
    }

    @Test
    void asigna_al_conductor_con_sus_datos_del_token_y_de_firestore() throws Exception {
        givenRide("p1", "pending", null);
        driverProfile("d1", "v1", "fcm-d1", "es");
        vehicle("v1", "d1");
        passengerProfile("p1", "fcm-p1", "es");

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", driverToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("driverAssigned");
        assertThat(rideField("p1", "updatedAt")).isEqualTo(FakeRealtimeDatabase.FIXED_SERVER_TIME);
        assertThat(rtdb.getMap(RIDES + "/p1/driver/data")).isEqualTo(Map.of(
                "id", "d1",
                "email", "d1@test.com",
                "displayName", "Carlos Conductor",
                "photoUrl", "https://foto/d1.jpg",
                "phoneNumber", "0990000000",
                "vehiclePlate", "PBX-1234",
                "vehicleBrand", "Toyota",
                "vehicleModel", "Corolla",
                "vehicleColor", "Amarillo"
        ));
        assertThat(rideField("p1", "driver/location/latitude")).isEqualTo(-1.67);
        assertThat(rideField("p1", "driver/location/longitude")).isEqualTo(-78.65);
    }

    // Distancia haversine entre el conductor y el punto de recogida, en metros.
    @Test
    void calcula_la_distancia_inicial_al_punto_de_recogida() throws Exception {
        givenRide("p1", "pending", null);

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", driverToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isNoContent());

        // (-1.67, -78.65) -> (-1.6635, -78.6546): unos 880 m.
        Number distance = (Number) rideField("p1", "driver/initialDistance");
        assertThat(distance.doubleValue()).isBetween(850.0, 910.0);
    }

    @Test
    void notifica_al_pasajero_en_su_idioma() throws Exception {
        givenRide("p1", "pending", null);
        passengerProfile("p1", "fcm-p1", "en");

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", driverToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isNoContent());

        List<SentPush> pushes = sentPushes();
        assertThat(pushes).hasSize(1);
        SentPush push = pushes.get(0);
        assertThat(push.token()).isEqualTo("fcm-p1");
        assertThat(push.title()).isEqualTo("Ride accepted!");
        assertThat(push.body()).isEqualTo("A driver is on the way to pick you up");
        assertThat(push.data()).containsEntry("route", "/ride_tracking")
                .containsEntry("type", "")
                .containsEntry("rideId", "");
    }

    @Test
    void crea_el_hilo_de_chat_de_la_carrera() throws Exception {
        givenRide("p1", "pending", null);

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", driverToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isNoContent());

        Map<String, Object> chat = firestore.get("chats/p1_1700000000000");
        assertThat(chat).isNotNull();
        assertThat(chat).containsEntry("passengerId", "p1").containsEntry("driverId", "d1");
        assertThat(chat.get("createdAt")).isNotNull();
    }

    // Teléfono y vehículo son datos complementarios: si faltan, se asigna igual.
    @Test
    void asigna_igual_si_el_conductor_no_tiene_perfil_ni_vehiculo() throws Exception {
        givenRide("p1", "pending", null);

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", driverToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("driverAssigned");
        Map<String, Object> data = rtdb.getMap(RIDES + "/p1/driver/data");
        assertThat(data).containsEntry("id", "d1")
                .doesNotContainKeys("phoneNumber", "vehiclePlate", "vehicleBrand", "vehicleModel", "vehicleColor");
    }

    @Test
    void sin_push_si_el_pasajero_no_tiene_token_fcm() throws Exception {
        givenRide("p1", "pending", null);
        passengerProfile("p1", null, "es");

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", driverToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isNoContent());

        assertThat(sentPushes()).isEmpty();
    }

    @Test
    void responde_409_si_ya_la_acepto_otro_conductor() throws Exception {
        givenRide("p1", "driverAssigned", "d2");

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", driverToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isConflict());

        assertThat(rideField("p1", "driver/data/id")).isEqualTo("d2");
        assertThat(sentPushes()).isEmpty();
        assertThat(firestore.documentIds("chats")).isEmpty();
    }

    @Test
    void responde_409_si_la_solicitud_ya_fue_cancelada() throws Exception {
        givenRide("p1", "cancelled", null);

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", driverToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isConflict());
    }

    @Test
    void responde_404_si_no_hay_solicitud() throws Exception {
        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", driverToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isNotFound());

        assertThat(rtdb.get(RIDES + "/p1")).isNull();
    }

    // Bug conocido (se corrige en la fase de seguridad): no se verifica que
    // quien acepta sea un conductor, ni que esté aprobado o no bloqueado.
    // Cualquier usuario autenticado puede aceptar, incluso el propio pasajero.
    @Test
    void bug_conocido_cualquier_usuario_autenticado_puede_aceptar() throws Exception {
        givenRide("p1", "pending", null);

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "driver/data/id")).isEqualTo("p1");
    }

    @Test
    void bug_conocido_un_conductor_bloqueado_puede_aceptar() throws Exception {
        givenRide("p1", "pending", null);
        firestore.put("drivers/d1", Map.of("isBlocked", true, "approvalStatus", "approved"));

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", driverToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("driverAssigned");
    }

    @Test
    void sin_token_responde_401() throws Exception {
        givenRide("p1", "pending", null);

        mvc.perform(post("/api/rides/p1/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isUnauthorized());

        assertThat(rideField("p1", "status")).isEqualTo("pending");
    }
}
