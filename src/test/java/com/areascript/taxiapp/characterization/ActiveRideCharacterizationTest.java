package com.areascript.taxiapp.characterization;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /api/rides/passenger/active y /api/rides/driver/active: las apps los
// llaman al reabrir para retomar un viaje en curso. Devuelven el nodo crudo de
// Realtime DB (RideEntity.fromJson de passenger_app lo parsea tal cual).
class ActiveRideCharacterizationTest extends CharacterizationTestBase {

    // ---- Pasajero ----

    @Test
    void pasajero_sin_solicitud_responde_204() throws Exception {
        mvc.perform(get("/api/rides/passenger/active").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());
    }

    // Para el pasajero, 'pending' cuenta como viaje en curso: al reabrir
    // retoma el diálogo "Buscando conductor".
    @Test
    void pasajero_con_solicitud_pending_recibe_el_nodo_crudo() throws Exception {
        givenRide("p1", "pending", null);

        mvc.perform(get("/api/rides/passenger/active").header("Authorization", userToken("p1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.rideId").value("p1_1700000000000"))
                .andExpect(jsonPath("$.userId").value("p1"))
                .andExpect(jsonPath("$.pickupLocation.address").value("Av. Daniel León Borja"))
                .andExpect(jsonPath("$.pickupLocation.sector").value("La Saboya"))
                .andExpect(jsonPath("$.passenger.name").value("Pasajero p1"));
    }

    @Test
    void pasajero_con_viaje_en_curso_recibe_los_datos_del_conductor() throws Exception {
        givenRide("p1", "tripStarted", "d1");

        mvc.perform(get("/api/rides/passenger/active").header("Authorization", userToken("p1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("tripStarted"))
                .andExpect(jsonPath("$.driver.data.id").value("d1"))
                .andExpect(jsonPath("$.driver.location.latitude").value(-1.67));
    }

    @Test
    void pasajero_con_viaje_cancelado_o_completado_responde_204() throws Exception {
        givenRide("p1", "cancelled", null);
        mvc.perform(get("/api/rides/passenger/active").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());

        givenRide("p1", "tripCompleted", "d1");
        mvc.perform(get("/api/rides/passenger/active").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());
    }

    // El passengerId sale del token: no hay forma de consultar el de otro.
    @Test
    void pasajero_solo_ve_su_propio_nodo() throws Exception {
        givenRide("p2", "pending", null);

        mvc.perform(get("/api/rides/passenger/active").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());
    }

    // ---- Conductor ----

    @Test
    void conductor_con_viaje_asignado_recibe_ese_viaje() throws Exception {
        givenRide("p1", "pending", null);
        givenRide("p2", "driverArrived", "d1");
        givenRide("p3", "driverAssigned", "d2");

        mvc.perform(get("/api/rides/driver/active").header("Authorization", userToken("d1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("p2"))
                .andExpect(jsonPath("$.status").value("driverArrived"));
    }

    @Test
    void conductor_sin_viaje_asignado_responde_204() throws Exception {
        givenRide("p1", "pending", null);
        givenRide("p3", "driverAssigned", "d2");

        mvc.perform(get("/api/rides/driver/active").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());
    }

    @Test
    void conductor_con_viaje_terminado_responde_204() throws Exception {
        givenRide("p1", "tripCompleted", "d1");
        givenRide("p2", "cancelled", "d1");

        mvc.perform(get("/api/rides/driver/active").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());
    }

    @Test
    void conductor_sin_ninguna_solicitud_responde_204() throws Exception {
        mvc.perform(get("/api/rides/driver/active").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());
    }

    @Test
    void sin_token_responde_401() throws Exception {
        mvc.perform(get("/api/rides/driver/active"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rides/passenger/active"))
                .andExpect(status().isUnauthorized());
    }
}
