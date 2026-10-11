package com.areascript.taxiapp.ride.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RideStatusTest {

    @Test
    void parsea_los_valores_que_escriben_las_apps() {
        assertThat(RideStatus.parse("pending")).contains(RideStatus.PENDING);
        assertThat(RideStatus.parse("driverAssigned")).contains(RideStatus.DRIVER_ASSIGNED);
        assertThat(RideStatus.parse("driverArrived")).contains(RideStatus.DRIVER_ARRIVED);
        assertThat(RideStatus.parse("tripStarted")).contains(RideStatus.TRIP_STARTED);
        assertThat(RideStatus.parse("tripCompleted")).contains(RideStatus.TRIP_COMPLETED);
        assertThat(RideStatus.parse("cancelled")).contains(RideStatus.CANCELLED);
    }

    @Test
    void un_valor_desconocido_o_nulo_no_se_parsea() {
        assertThat(RideStatus.parse("quizas")).isEmpty();
        assertThat(RideStatus.parse("PENDING")).isEmpty();
        assertThat(RideStatus.parse(null)).isEmpty();
    }

    @Test
    void solo_cancelled_y_tripCompleted_son_terminales() {
        assertThat(RideStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(RideStatus.TRIP_COMPLETED.isTerminal()).isTrue();
        assertThat(RideStatus.PENDING.isTerminal()).isFalse();
        assertThat(RideStatus.DRIVER_ASSIGNED.isTerminal()).isFalse();
        assertThat(RideStatus.TRIP_STARTED.isTerminal()).isFalse();
    }

    // Lo que cuenta como viaje en curso: con conductor y sin terminar.
    @Test
    void en_curso_es_con_conductor_asignado_y_sin_terminar() {
        assertThat(RideStatus.DRIVER_ASSIGNED.isInProgress()).isTrue();
        assertThat(RideStatus.DRIVER_ARRIVED.isInProgress()).isTrue();
        assertThat(RideStatus.TRIP_STARTED.isInProgress()).isTrue();
        assertThat(RideStatus.PENDING.isInProgress()).isFalse();
        assertThat(RideStatus.CANCELLED.isInProgress()).isFalse();
        assertThat(RideStatus.TRIP_COMPLETED.isInProgress()).isFalse();
    }

    // Para el pasajero, la solicitud pendiente también cuenta: al reabrir
    // retoma el diálogo "Buscando conductor".
    @Test
    void para_el_pasajero_la_solicitud_pendiente_tambien_esta_activa() {
        assertThat(RideStatus.PENDING.isActiveForPassenger()).isTrue();
        assertThat(RideStatus.DRIVER_ASSIGNED.isActiveForPassenger()).isTrue();
        assertThat(RideStatus.CANCELLED.isActiveForPassenger()).isFalse();
        assertThat(RideStatus.TRIP_COMPLETED.isActiveForPassenger()).isFalse();
    }

    // Un estado que el servidor no reconoce no cuenta como nada.
    @Test
    void una_carrera_con_estado_desconocido_no_esta_activa_ni_terminada() {
        Ride unknown = new Ride("p1", "p1_1", null, null, null, null, null, java.util.Map.of());

        assertThat(unknown.isInProgress()).isFalse();
        assertThat(unknown.isActiveForPassenger()).isFalse();
        assertThat(unknown.isTerminal()).isFalse();
    }
}
