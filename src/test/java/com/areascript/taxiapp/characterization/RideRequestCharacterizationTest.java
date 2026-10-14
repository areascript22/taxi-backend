package com.areascript.taxiapp.characterization;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.areascript.taxiapp.characterization.support.FakeRealtimeDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /api/rides/request: el pasajero pide un taxi.
class RideRequestCharacterizationTest extends CharacterizationTestBase {

    private static final String BODY = """
            {"latitude": -1.6635, "longitude": -78.6546,
             "address": "Av. Daniel León Borja", "sector": "La Saboya"}
            """;

    @Test
    void crea_la_solicitud_pending_en_el_nodo_del_pasajero() throws Exception {
        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());

        Map<String, Object> ride = rtdb.getMap(RIDES + "/p1");
        assertThat(ride).isNotNull();
        assertThat(ride.get("status")).isEqualTo("pending");
        assertThat(ride.get("userId")).isEqualTo("p1");
        assertThat((String) ride.get("rideId")).startsWith("p1_");
        assertThat(ride.get("createdAt")).isEqualTo(FakeRealtimeDatabase.FIXED_SERVER_TIME);
        assertThat(ride.get("updatedAt")).isEqualTo(FakeRealtimeDatabase.FIXED_SERVER_TIME);
        assertThat(ride.get("driver")).isNull();

        assertThat(rideField("p1", "pickupLocation/latitude")).isEqualTo(-1.6635);
        assertThat(rideField("p1", "pickupLocation/longitude")).isEqualTo(-78.6546);
        assertThat(rideField("p1", "pickupLocation/address")).isEqualTo("Av. Daniel León Borja");
        assertThat(rideField("p1", "pickupLocation/sector")).isEqualTo("La Saboya");
    }

    // El nombre y la foto salen del token verificado, no del body.
    @Test
    void nombre_y_foto_del_pasajero_salen_del_token() throws Exception {
        String token = token("p1", "p1@test.com", "Ana Pérez", "https://foto/ana.jpg", Map.of());

        mvc.perform(post("/api/rides/request")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "passenger/name")).isEqualTo("Ana Pérez");
        assertThat(rideField("p1", "passenger/profileImage")).isEqualTo("https://foto/ana.jpg");
    }

    @Test
    void quita_el_plus_code_del_inicio_de_la_direccion() throws Exception {
        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude": -1.66, "longitude": -78.65,
                                 "address": "GX7Q+2X Riobamba, Ecuador", "sector": "Centro"}
                                """))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "pickupLocation/address")).isEqualTo("Riobamba, Ecuador");
    }

    @Test
    void normaliza_espacios_del_sector_y_lo_corta_a_80_caracteres() throws Exception {
        String longSector = "x".repeat(100);

        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": -1.66, \"longitude\": -78.65, \"address\": \"A\", \"sector\": \"  Tierra \\u00a0 nueva  \"}"))
                .andExpect(status().isNoContent());
        assertThat(rideField("p1", "pickupLocation/sector")).isEqualTo("Tierra nueva");

        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": -1.66, \"longitude\": -78.65, \"address\": \"A\", \"sector\": \"" + longSector + "\"}"))
                .andExpect(status().isNoContent());
        assertThat((String) rideField("p2", "pickupLocation/sector")).hasSize(80);
    }

    // Sector y dirección ausentes no son error: se guardan vacíos.
    @Test
    void sector_y_direccion_null_se_guardan_vacios() throws Exception {
        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": -1.66, \"longitude\": -78.65}"))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "pickupLocation/address")).isEqualTo("");
        assertThat(rideField("p1", "pickupLocation/sector")).isEqualTo("");
    }

    @Test
    void agenda_la_auto_cancelacion_35_segundos_despues() throws Exception {
        Instant before = Instant.now();

        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());

        List<ScheduledTask> tasks = scheduledTasks();
        assertThat(tasks).hasSize(1);
        Duration delay = Duration.between(before, tasks.get(0).at());
        assertThat(delay).isBetween(Duration.ofSeconds(34), Duration.ofSeconds(36));
    }

    @Test
    void la_auto_cancelacion_cancela_si_sigue_pending() throws Exception {
        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());

        scheduledTasks().get(0).task().run();

        assertThat(rideField("p1", "status")).isEqualTo("cancelled");
        assertThat(rideField("p1", "cancelledBy")).isEqualTo("system");
        assertThat(sentPushes()).isEmpty();
    }

    @Test
    void la_auto_cancelacion_no_toca_una_carrera_ya_aceptada() throws Exception {
        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());
        rtdb.put(RIDES + "/p1/status", "driverAssigned");

        scheduledTasks().get(0).task().run();

        assertThat(rideField("p1", "status")).isEqualTo("driverAssigned");
        assertThat(rideField("p1", "cancelledBy")).isNull();
    }

    // Fase 9: la tarea de vencimiento lleva el rideId de SU solicitud. Si el
    // pasajero cancela y vuelve a pedir dentro de los 35 s, la tarea de la
    // primera ya no puede cancelar a la segunda antes de tiempo.
    @Test
    void la_tarea_de_una_solicitud_vieja_no_cancela_la_nueva() throws Exception {
        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());
        Runnable firstRequestExpiry = scheduledTasks().get(0).task();
        Object firstRideId = rideField("p1", "rideId");

        // El rideId lleva los milisegundos: se separan para que sean distintos.
        Thread.sleep(5);
        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());
        assertThat(rideField("p1", "rideId")).isNotEqualTo(firstRideId);

        firstRequestExpiry.run();

        assertThat(rideField("p1", "status")).isEqualTo("pending");
        assertThat(rideField("p1", "cancelledBy")).isNull();
    }

    // ...pero la tarea de la solicitud ACTUAL sí la vence.
    @Test
    void la_tarea_de_la_solicitud_actual_si_la_cancela() throws Exception {
        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());
        Thread.sleep(5);
        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());

        List<ScheduledTask> tasks = scheduledTasks();
        tasks.get(1).task().run();

        assertThat(rideField("p1", "status")).isEqualTo("cancelled");
        assertThat(rideField("p1", "cancelledBy")).isEqualTo("system");
    }

    // Fase 9: pedir otro taxi no puede borrarle al pasajero el conductor que
    // ya lo está esperando.
    @Test
    void una_solicitud_nueva_no_pisa_un_viaje_en_curso() throws Exception {
        for (String status : new String[]{"driverAssigned", "driverArrived", "tripStarted"}) {
            givenRide("p1", status, "d1");

            mvc.perform(post("/api/rides/request")
                            .header("Authorization", userToken("p1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isConflict());

            assertThat(rideField("p1", "status")).isEqualTo(status);
            assertThat(rideField("p1", "driver/data/id")).isEqualTo("d1");
        }
        assertThat(scheduledTasks()).isEmpty();
    }

    // Una solicitud todavía pendiente (el mismo pasajero volviendo a pedir) o
    // una carrera terminada que espera su limpieza sí se reemplazan, y la
    // nueva no hereda nada de la anterior.
    @Test
    void una_solicitud_nueva_reemplaza_una_pendiente_o_una_terminada() throws Exception {
        givenRide("p1", "cancelled", "d1");
        rtdb.put(RIDES + "/p1/cancelledBy", "driver");

        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());

        assertThat(rideField("p1", "status")).isEqualTo("pending");
        assertThat(rideField("p1", "driver")).isNull();
        assertThat(rideField("p1", "cancelledBy")).isNull();

        givenRide("p2", "pending", null);
        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNoContent());
        assertThat(rideField("p2", "status")).isEqualTo("pending");
    }

    @Test
    void sin_token_responde_401_y_no_escribe_nada() throws Exception {
        mvc.perform(post("/api/rides/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        assertThat(rtdb.get(RIDES)).isNull();
    }
}
