package com.areascript.taxiapp.ride.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// La regla "publicar la ubicación del conductor en la carrera": función pura,
// sin Firebase ni Spring.
class RideTransitionsLocationTest {

    private static Ride ride(RideStatus status, String driverId) {
        return new Ride("p1", "p1_1", status, driverId, null, -1.66, -78.65, Map.of());
    }

    @Test
    void sin_carrera_no_escribe_nada() {
        RideChange change = RideTransitions.updateDriverLocation(null, "d1", -1.0, -78.0);

        assertThat(change).isInstanceOf(RideChange.Unchanged.class);
    }

    @Test
    void el_conductor_asignado_escribe_solo_la_ubicacion() {
        RideChange change = RideTransitions.updateDriverLocation(
                ride(RideStatus.DRIVER_ASSIGNED, "d1"), "d1", -1.67, -78.66);

        assertThat(change).isInstanceOf(RideChange.Update.class);
        Map<String, Object> fields = ((RideChange.Update) change).fields();
        assertThat(fields).containsEntry("driver/location/latitude", -1.67);
        assertThat(fields).containsEntry("driver/location/longitude", -78.66);
        assertThat(fields).containsEntry("driver/location/updatedAt", ServerTime.NOW);
        // No es un cambio de estado: no toca el estado ni el updatedAt de la carrera.
        assertThat(fields).doesNotContainKeys("status", "updatedAt");
    }

    @Test
    void funciona_en_todos_los_estados_en_curso() {
        for (RideStatus status : new RideStatus[]{
                RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVED, RideStatus.TRIP_STARTED}) {
            assertThat(RideTransitions.updateDriverLocation(ride(status, "d1"), "d1", -1.0, -78.0))
                    .as(status.name()).isInstanceOf(RideChange.Update.class);
        }
    }

    @Test
    void otro_conductor_es_prohibido() {
        RideChange change = RideTransitions.updateDriverLocation(
                ride(RideStatus.DRIVER_ASSIGNED, "d1"), "intruso", -1.0, -78.0);

        assertThat(change).isEqualTo(RideChange.rejected(RideChange.Reason.FORBIDDEN));
    }

    @Test
    void una_carrera_pendiente_o_terminada_no_admite_ubicacion() {
        assertThat(RideTransitions.updateDriverLocation(ride(RideStatus.PENDING, null), "d1", -1.0, -78.0))
                .isEqualTo(RideChange.rejected(RideChange.Reason.NOT_ALLOWED));
        assertThat(RideTransitions.updateDriverLocation(ride(RideStatus.TRIP_COMPLETED, "d1"), "d1", -1.0, -78.0))
                .isEqualTo(RideChange.rejected(RideChange.Reason.NOT_ALLOWED));
        assertThat(RideTransitions.updateDriverLocation(ride(RideStatus.CANCELLED, "d1"), "d1", -1.0, -78.0))
                .isEqualTo(RideChange.rejected(RideChange.Reason.NOT_ALLOWED));
    }
}
