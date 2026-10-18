package com.areascript.taxiapp.dispatch.application;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.areascript.taxiapp.characterization.support.FakeDispatchPlans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// El despacho con taxiapp.dispatch.enforce-offers=true: la fase final, cuando
// ya no hay apps viejas. Solo acepta quien recibió la oferta y, si nadie lo
// hace, la solicitud se cierra con "no hay vehículos".
@TestPropertySource(properties = "taxiapp.dispatch.enforce-offers=true")
class RingDispatchEnforcedTest extends CharacterizationTestBase {

    private static final String JSON = "application/json";
    private static final double LAT = -1.6635;
    private static final double LNG = -78.6546;

    @BeforeEach
    void defaultPlan() {
        dispatchPlans.use(FakeDispatchPlans.standard());
    }

    private void availableDriver(String uid, double northDegrees) {
        firestore.put("drivers/" + uid, Map.of("firstName", uid, "approvalStatus", "approved"));
        presence.register(uid);
        presence.setOnline(uid, true);
        presence.recordLocationIfAvailable(uid, LAT + northDegrees, LNG);
    }

    private void requestRide(String passengerUid) throws Exception {
        mvc.perform(post("/api/rides/request").header("Authorization", userToken(passengerUid))
                        .contentType(JSON)
                        .content("{\"latitude\":" + LAT + ",\"longitude\":" + LNG
                                + ",\"address\":\"Av. Daniel Leon Borja\",\"sector\":\"La Saboya\"}"))
                .andExpect(status().isNoContent());
    }

    private void runTask(int index) {
        List<ScheduledTask> tasks = scheduledTasks();
        assertThat(tasks).hasSizeGreaterThan(index);
        tasks.get(index).task().run();
    }

    private int accept(String driverUid, String passengerId) throws Exception {
        return mvc.perform(post("/api/rides/" + passengerId + "/accept")
                        .header("Authorization", userToken(driverUid))
                        .contentType(JSON).content("{\"latitude\":-1.66,\"longitude\":-78.65}"))
                .andReturn().getResponse().getStatus();
    }

    // ---- Aceptar ----

    @Test
    void quien_recibio_la_oferta_puede_aceptar() throws Exception {
        availableDriver("cerca", 0.0045);
        requestRide("p1");
        runTask(1);

        assertThat(accept("cerca", "p1")).isEqualTo(204);
        assertThat(rideField("p1", "status")).isEqualTo("driverAssigned");
    }

    @Test
    void quien_no_la_recibio_no_puede_aceptar_aunque_este_aprobado() throws Exception {
        availableDriver("cerca", 0.0045);
        availableDriver("lejos", 0.18);                 // a 20 km: sin oferta
        requestRide("p1");
        runTask(1);

        assertThat(accept("lejos", "p1")).isEqualTo(403);
        assertThat(rideField("p1", "status")).as("la carrera sigue libre").isEqualTo("pending");
    }

    // Una oferta vieja del mismo pasajero no autoriza una solicitud nueva.
    @Test
    void una_oferta_de_otra_solicitud_no_autoriza() throws Exception {
        availableDriver("cerca", 0.0045);
        requestRide("p1");
        rtdb.put("driver_offers/cerca/p1", Map.of("rideId", "p1_solicitud_vieja"));

        assertThat(accept("cerca", "p1")).isEqualTo(403);
    }

    @Test
    void aceptar_una_carrera_que_no_existe_sigue_siendo_404() throws Exception {
        availableDriver("cerca", 0.0045);

        assertThat(accept("cerca", "nadie")).isEqualTo(404);
    }

    // ---- Nadie acepta ----

    @Test
    void agotados_los_anillos_la_solicitud_se_cierra_sin_vehiculos_disponibles() throws Exception {
        availableDriver("cerca", 0.0045);
        requestRide("p1");

        runTask(1);
        runTask(2);
        runTask(3);
        runTask(4);                                     // sin anillos: nadie aceptó

        assertThat(rideField("p1", "status")).isEqualTo("cancelled");
        assertThat(rideField("p1", "cancelledBy")).isEqualTo("system");
        assertThat(rideField("p1", "cancelReason")).isEqualTo("noDrivers");
        assertThat(rtdb.get("driver_offers/cerca/p1")).as("ofertas retiradas").isNull();
    }

    @Test
    void al_pasajero_se_le_avisa_que_no_hay_vehiculos() throws Exception {
        passengerProfile("p1", "fcm-p1", "es");
        availableDriver("cerca", 0.0045);
        requestRide("p1");

        runTask(1);
        runTask(2);
        runTask(3);
        runTask(4);

        assertThat(sentPushes()).anySatisfy(push ->
                assertThat(push.title()).isEqualTo("No hay vehículos disponibles"));
    }

    // Si alguien aceptó justo antes del cierre, no se pisa la carrera.
    @Test
    void no_cierra_una_solicitud_que_ya_fue_aceptada() throws Exception {
        availableDriver("cerca", 0.0045);
        requestRide("p1");
        runTask(1);

        assertThat(accept("cerca", "p1")).isEqualTo(204);
        runTask(2);                     // el anillo 2: la búsqueda ya terminó
        assertThat(scheduledTasks()).as("no agenda más anillos").hasSize(3);

        assertThat(rideField("p1", "status")).isEqualTo("driverAssigned");
    }
}
