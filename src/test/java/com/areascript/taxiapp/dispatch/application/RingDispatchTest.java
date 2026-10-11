package com.areascript.taxiapp.dispatch.application;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.areascript.taxiapp.characterization.support.FakeDispatchPlans;
import com.areascript.taxiapp.dispatch.domain.DispatchPlan;
import com.areascript.taxiapp.dispatch.domain.DispatchRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// El despacho por anillos de punta a punta a nivel HTTP: el pasajero pide un
// taxi, los temporizadores corren cuando el test lo decide (el TaskScheduler es
// un mock que los captura) y se mira qué quedó en la bandeja de cada conductor.
//
// Orden de los temporizadores al pedir un taxi:
//   [0] vencimiento de la solicitud (35 s)
//   [1] anillo 1, de inmediato
//   [2] anillo 2 (lo agenda el anillo 1 al correr), y así sucesivamente
class RingDispatchTest extends CharacterizationTestBase {

    private static final String JSON = "application/json";

    // El punto de recogida: la Plaza de Riobamba.
    private static final double LAT = -1.6635;
    private static final double LNG = -78.6546;

    // 0,0045° de latitud son ~500 m; 0,045° son ~5 km; 0,18° son ~20 km.
    private static final double NEAR = 0.0045;
    private static final double MID = 0.045;
    private static final double FAR = 0.18;

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
        assertThat(tasks).as("temporizadores agendados").hasSizeGreaterThan(index);
        tasks.get(index).task().run();
    }

    private Object offer(String driverUid, String passengerId) {
        return rtdb.get("driver_offers/" + driverUid + "/" + passengerId);
    }

    private Object offerField(String driverUid, String passengerId, String field) {
        return rtdb.get("driver_offers/" + driverUid + "/" + passengerId + "/" + field);
    }

    // ---- Primer anillo ----

    @Test
    void el_primer_anillo_ofrece_solo_a_los_conductores_cercanos() throws Exception {
        availableDriver("cerca", NEAR);
        availableDriver("medio", MID);
        availableDriver("lejos", FAR);

        requestRide("p1");
        runTask(1);

        assertThat(offer("cerca", "p1")).isNotNull();
        assertThat(offer("medio", "p1")).as("a 5 km: fuera del anillo de 3 km").isNull();
        assertThat(offer("lejos", "p1")).isNull();
    }

    @Test
    void la_oferta_lleva_lo_necesario_para_decidir() throws Exception {
        availableDriver("cerca", NEAR);

        requestRide("p1");
        runTask(1);

        assertThat(offerField("cerca", "p1", "passengerId")).isEqualTo("p1");
        assertThat(offerField("cerca", "p1", "rideId")).isEqualTo(rideField("p1", "rideId"));
        assertThat(offerField("cerca", "p1", "pickup/sector")).isEqualTo("La Saboya");
        assertThat(offerField("cerca", "p1", "pickup/latitude")).isEqualTo(LAT);
        assertThat(offerField("cerca", "p1", "ring")).isEqualTo(1L);
        assertThat((Double) offerField("cerca", "p1", "distanceMeters")).isBetween(450.0, 550.0);
        assertThat(offerField("cerca", "p1", "expiresAt")).isNotNull();
    }

    @Test
    void no_ofrece_a_quien_esta_fuera_de_linea_ni_en_carrera() throws Exception {
        availableDriver("libre", NEAR);
        availableDriver("desconectado", NEAR);
        availableDriver("ocupado", NEAR);
        presence.setOnline("desconectado", false);
        presence.setOnRide("ocupado", true);

        requestRide("p1");
        runTask(1);

        assertThat(offer("libre", "p1")).isNotNull();
        assertThat(offer("desconectado", "p1")).isNull();
        assertThat(offer("ocupado", "p1")).isNull();
    }

    @Test
    void respeta_el_maximo_de_conductores_del_anillo() throws Exception {
        dispatchPlans.use(new DispatchPlan(List.of(
                new DispatchRing(1, 3000, Duration.ofSeconds(10), 2)), Duration.ofSeconds(45)));
        availableDriver("a", 0.0010);
        availableDriver("b", 0.0020);
        availableDriver("c", 0.0030);

        requestRide("p1");
        runTask(1);

        assertThat(offer("a", "p1")).isNotNull();
        assertThat(offer("b", "p1")).isNotNull();
        assertThat(offer("c", "p1")).as("el tercero queda fuera: tope de 2").isNull();
    }

    // ---- Ampliar ----

    @Test
    void el_segundo_anillo_amplia_el_radio_y_solo_suma_a_los_nuevos() throws Exception {
        availableDriver("cerca", NEAR);
        availableDriver("medio", MID);
        availableDriver("lejos", FAR);

        requestRide("p1");
        runTask(1);                     // anillo 1
        runTask(2);                     // anillo 2

        assertThat(offer("medio", "p1")).as("ahora entra el de 5 km").isNotNull();
        assertThat(offerField("medio", "p1", "ring")).isEqualTo(2L);
        assertThat(offerField("cerca", "p1", "ring")).as("el del anillo 1 conserva su oferta").isEqualTo(1L);
        assertThat(offer("lejos", "p1")).as("a 20 km: fuera de los 6 km").isNull();
    }

    @Test
    void el_tercer_anillo_llega_hasta_los_9_km() throws Exception {
        availableDriver("a-8km", 0.072);

        requestRide("p1");
        runTask(1);
        assertThat(offer("a-8km", "p1")).isNull();
        runTask(2);
        assertThat(offer("a-8km", "p1")).isNull();
        runTask(3);

        assertThat(offer("a-8km", "p1")).isNotNull();
    }

    @Test
    void cada_anillo_espera_su_propio_tiempo() throws Exception {
        dispatchPlans.use(new DispatchPlan(List.of(
                new DispatchRing(1, 3000, Duration.ofSeconds(7), 10),
                new DispatchRing(2, 6000, Duration.ofSeconds(20), 10)), Duration.ofSeconds(45)));
        availableDriver("cerca", NEAR);

        requestRide("p1");
        runTask(1);

        // [2] es el anillo 2, agendado 7 s despues del 1.
        List<ScheduledTask> tasks = scheduledTasks();
        Duration gap = Duration.between(tasks.get(1).at(), tasks.get(2).at());
        assertThat(gap.toSeconds()).isBetween(6L, 8L);
    }

    // ---- Terminar la búsqueda ----

    @Test
    void cuando_alguien_acepta_se_retiran_las_ofertas_y_no_se_amplia() throws Exception {
        availableDriver("cerca", NEAR);
        availableDriver("tambien-cerca", NEAR * 2);
        availableDriver("medio", MID);

        requestRide("p1");
        runTask(1);
        assertThat(offer("tambien-cerca", "p1")).isNotNull();

        mvc.perform(post("/api/rides/p1/accept").header("Authorization", userToken("cerca"))
                        .contentType(JSON).content("{\"latitude\":-1.66,\"longitude\":-78.65}"))
                .andExpect(status().isNoContent());

        assertThat(offer("cerca", "p1")).isNull();
        assertThat(offer("tambien-cerca", "p1")).as("el otro ya no la ve").isNull();

        runTask(2);                     // el anillo 2 ya no debe ofrecer nada
        assertThat(offer("medio", "p1")).isNull();
    }

    @Test
    void cuando_el_pasajero_cancela_se_retiran_las_ofertas_y_no_se_amplia() throws Exception {
        availableDriver("cerca", NEAR);
        availableDriver("medio", MID);

        requestRide("p1");
        runTask(1);
        assertThat(offer("cerca", "p1")).isNotNull();

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());

        assertThat(offer("cerca", "p1")).isNull();
        runTask(2);
        assertThat(offer("medio", "p1")).as("no se amplia una solicitud cancelada").isNull();
    }

    @Test
    void cuando_la_solicitud_vence_se_retiran_las_ofertas() throws Exception {
        availableDriver("cerca", NEAR);

        requestRide("p1");
        runTask(1);
        assertThat(offer("cerca", "p1")).isNotNull();

        runTask(0);                     // vence la solicitud (35 s)

        assertThat(offer("cerca", "p1")).isNull();
    }

    // Sin enforce-offers (el valor por defecto): las apps viejas siguen viendo
    // todos los pedidos, así que "nadie cercano aceptó" no es "no hay vehículos".
    @Test
    void agotar_los_anillos_no_cancela_la_solicitud_mientras_no_se_exija_la_oferta() throws Exception {
        availableDriver("cerca", NEAR);

        requestRide("p1");
        runTask(1);
        runTask(2);
        runTask(3);
        runTask(4);                     // se acabaron los anillos

        assertThat(rideField("p1", "status")).isEqualTo("pending");
        assertThat(offer("cerca", "p1")).as("las ofertas ya no se muestran").isNull();
    }

    // ---- Configuración ----

    @Test
    void un_cambio_de_configuracion_no_altera_una_busqueda_en_curso() throws Exception {
        availableDriver("medio", MID);

        requestRide("p1");
        runTask(1);                     // arranca con 3/6/9 km

        // El administrador deja un solo anillo de 1 km a mitad de la búsqueda.
        dispatchPlans.use(new DispatchPlan(List.of(
                new DispatchRing(1, 1000, Duration.ofSeconds(10), 10)), Duration.ofSeconds(45)));
        runTask(2);                     // el anillo 2 de ESTA búsqueda sigue siendo de 6 km

        assertThat(offer("medio", "p1")).isNotNull();
    }

    @Test
    void sin_anillos_configurados_no_se_despacha_nada() throws Exception {
        dispatchPlans.clear();
        availableDriver("cerca", NEAR);

        requestRide("p1");

        assertThat(scheduledTasks()).as("solo el vencimiento").hasSize(1);
        assertThat(offer("cerca", "p1")).isNull();
    }

    @Test
    void pedir_de_nuevo_reemplaza_la_busqueda_anterior() throws Exception {
        availableDriver("cerca", NEAR);

        requestRide("p1");
        runTask(1);
        assertThat(offer("cerca", "p1")).isNotNull();
        Object firstRideId = offerField("cerca", "p1", "rideId");

        mvc.perform(post("/api/rides/p1/cancel").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());
        // El reloj puede dar el mismo milisegundo: el rideId se arma con la hora.
        Thread.sleep(5);
        requestRide("p1");

        assertThat(offer("cerca", "p1")).as("la anterior se retiró").isNull();
        runTask(scheduledTasks().size() - 1);      // el anillo 1 de la nueva
        assertThat(offerField("cerca", "p1", "rideId")).isNotEqualTo(firstRideId);
    }
}
