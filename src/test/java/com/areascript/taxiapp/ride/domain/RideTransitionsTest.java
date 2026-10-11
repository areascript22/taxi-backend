package com.areascript.taxiapp.ride.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Las reglas de una carrera, sin Firebase ni Spring: dado el estado actual,
// qué decide cada operación. Es lo que antes solo se podía ejercitar a través
// de un Transaction.Handler del SDK.
class RideTransitionsTest {

    private static final DriverContact DRIVER = new DriverContact(
            "d1", "d1@test.com", "Carlos", "https://foto", "0990000000", "PBX-1234", "Toyota", "Corolla", "Amarillo");

    private static Ride ride(RideStatus status, String driverId) {
        return new Ride("p1", "p1_1", status, driverId, null, -1.6635, -78.6546, Map.of());
    }

    private static RideChange.Update asUpdate(RideChange change) {
        assertThat(change).isInstanceOf(RideChange.Update.class);
        return (RideChange.Update) change;
    }

    private static void assertRejected(RideChange change, RideChange.Reason reason) {
        assertThat(change).isEqualTo(new RideChange.Rejected(reason));
    }

    // Una carrera ausente nunca se rechaza: el repositorio confirma sin
    // cambios y quien llamó detecta la ausencia (ver RideTransitions).
    @Test
    void sobre_una_carrera_ausente_ninguna_regla_cambia_nada() {
        assertThat(RideTransitions.assign(null, DRIVER, 0, 0)).isEqualTo(RideChange.unchanged());
        assertThat(RideTransitions.cancel(null, "p1")).isEqualTo(RideChange.unchanged());
        assertThat(RideTransitions.expire(null)).isEqualTo(RideChange.unchanged());
        assertThat(RideTransitions.complete(null, "d1")).isEqualTo(RideChange.unchanged());
        assertThat(RideTransitions.removeIfTerminal(null)).isEqualTo(RideChange.unchanged());
    }

    @Nested
    class Asignar {

        @Test
        void una_solicitud_pendiente_pasa_a_driverAssigned_con_los_datos_del_conductor() {
            RideChange.Update update = asUpdate(RideTransitions.assign(
                    ride(RideStatus.PENDING, null), DRIVER, -1.67, -78.65));

            Map<String, Object> fields = update.fields();
            assertThat(fields).containsEntry("status", "driverAssigned")
                    .containsEntry("updatedAt", ServerTime.NOW);
            assertThat(fields.get("driver/data")).isEqualTo(Map.of(
                    "id", "d1", "email", "d1@test.com", "displayName", "Carlos", "photoUrl", "https://foto",
                    "phoneNumber", "0990000000", "vehiclePlate", "PBX-1234", "vehicleBrand", "Toyota",
                    "vehicleModel", "Corolla", "vehicleColor", "Amarillo"));
            assertThat(fields.get("driver/location")).isEqualTo(Map.of("latitude", -1.67, "longitude", -78.65));
        }

        @Test
        void calcula_la_distancia_inicial_al_punto_de_recogida() {
            RideChange.Update update = asUpdate(RideTransitions.assign(
                    ride(RideStatus.PENDING, null), DRIVER, -1.67, -78.65));

            double meters = (Double) update.fields().get("driver/initialDistance");
            assertThat(meters).isBetween(850.0, 910.0);
        }

        // Sin coordenadas de recogida no hay distancia que calcular; no es un error.
        @Test
        void sin_punto_de_recogida_la_distancia_inicial_es_null() {
            Ride withoutPickup = new Ride("p1", "p1_1", RideStatus.PENDING, null, null, null, null, Map.of());

            RideChange.Update update = asUpdate(RideTransitions.assign(withoutPickup, DRIVER, -1.67, -78.65));

            assertThat(update.fields()).containsKey("driver/initialDistance");
            assertThat(update.fields().get("driver/initialDistance")).isNull();
        }

        // Si dos conductores aceptan a la vez, el segundo ve el estado ya
        // cambiado y se rechaza.
        @Test
        void solo_se_asigna_una_solicitud_pendiente() {
            for (RideStatus status : new RideStatus[]{
                    RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVED, RideStatus.TRIP_STARTED,
                    RideStatus.TRIP_COMPLETED, RideStatus.CANCELLED}) {
                assertRejected(
                        RideTransitions.assign(ride(status, "otro"), DRIVER, 0, 0),
                        RideChange.Reason.NOT_ALLOWED);
            }
        }

        @Test
        void un_estado_desconocido_tampoco_se_asigna() {
            assertRejected(
                    RideTransitions.assign(ride(null, null), DRIVER, 0, 0),
                    RideChange.Reason.NOT_ALLOWED);
        }
    }

    @Nested
    class Cancelar {

        @Test
        void el_pasajero_cancela_su_solicitud() {
            RideChange.Update update = asUpdate(RideTransitions.cancel(ride(RideStatus.PENDING, null), "p1"));

            assertThat(update.fields()).containsEntry("status", "cancelled")
                    .containsEntry("cancelledBy", "passenger")
                    .containsEntry("updatedAt", ServerTime.NOW);
        }

        @Test
        void el_conductor_asignado_cancela_en_cualquier_estado_en_curso() {
            for (RideStatus status : new RideStatus[]{
                    RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVED, RideStatus.TRIP_STARTED}) {
                RideChange.Update update = asUpdate(RideTransitions.cancel(ride(status, "d1"), "d1"));
                assertThat(update.fields()).containsEntry("cancelledBy", "driver");
            }
        }

        @Test
        void un_tercero_no_puede_cancelar() {
            assertRejected(
                    RideTransitions.cancel(ride(RideStatus.DRIVER_ASSIGNED, "d1"), "intruso"),
                    RideChange.Reason.FORBIDDEN);
        }

        @Test
        void un_conductor_no_asignado_a_esa_carrera_no_puede_cancelar() {
            assertRejected(
                    RideTransitions.cancel(ride(RideStatus.PENDING, null), "d1"),
                    RideChange.Reason.FORBIDDEN);
        }

        @Test
        void una_carrera_terminada_no_se_cancela() {
            assertRejected(
                    RideTransitions.cancel(ride(RideStatus.TRIP_COMPLETED, "d1"), "p1"),
                    RideChange.Reason.NOT_ALLOWED);
            assertRejected(
                    RideTransitions.cancel(ride(RideStatus.CANCELLED, "d1"), "p1"),
                    RideChange.Reason.NOT_ALLOWED);
        }

        // El estado terminal se evalúa antes que la identidad.
        @Test
        void un_tercero_sobre_una_carrera_terminada_recibe_no_permitido() {
            assertRejected(
                    RideTransitions.cancel(ride(RideStatus.CANCELLED, "d1"), "intruso"),
                    RideChange.Reason.NOT_ALLOWED);
        }
    }

    @Nested
    class Vencer {

        @Test
        void una_solicitud_pendiente_la_cancela_el_sistema() {
            RideChange.Update update = asUpdate(RideTransitions.expire(ride(RideStatus.PENDING, null)));

            assertThat(update.fields()).containsEntry("status", "cancelled")
                    .containsEntry("cancelledBy", "system");
        }

        // Ya la aceptó un conductor, o el pasajero ya la canceló: nada que hacer.
        @Test
        void una_solicitud_que_ya_no_esta_pendiente_no_vence() {
            assertRejected(
                    RideTransitions.expire(ride(RideStatus.DRIVER_ASSIGNED, "d1")),
                    RideChange.Reason.NOT_ALLOWED);
            assertRejected(
                    RideTransitions.expire(ride(RideStatus.CANCELLED, null)),
                    RideChange.Reason.NOT_ALLOWED);
        }
    }

    @Nested
    class Finalizar {

        @Test
        void el_conductor_asignado_finaliza_un_viaje_iniciado() {
            RideChange.Update update = asUpdate(RideTransitions.complete(ride(RideStatus.TRIP_STARTED, "d1"), "d1"));

            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("status", "tripCompleted");
            expected.put("updatedAt", ServerTime.NOW);
            assertThat(update.fields()).isEqualTo(expected);
        }

        @Test
        void otro_conductor_no_puede_finalizar() {
            assertRejected(
                    RideTransitions.complete(ride(RideStatus.TRIP_STARTED, "d1"), "d2"),
                    RideChange.Reason.FORBIDDEN);
        }

        @Test
        void solo_se_finaliza_un_viaje_ya_iniciado() {
            for (RideStatus status : new RideStatus[]{
                    RideStatus.PENDING, RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVED,
                    RideStatus.TRIP_COMPLETED, RideStatus.CANCELLED}) {
                assertRejected(
                        RideTransitions.complete(ride(status, "d1"), "d1"),
                        RideChange.Reason.NOT_ALLOWED);
            }
        }

        // El estado se valida antes que la identidad.
        @Test
        void un_tercero_sobre_un_viaje_no_iniciado_recibe_no_permitido() {
            assertRejected(
                    RideTransitions.complete(ride(RideStatus.DRIVER_ASSIGNED, "d1"), "intruso"),
                    RideChange.Reason.NOT_ALLOWED);
        }
    }

    @Nested
    class Limpiar {

        @Test
        void borra_una_carrera_terminada() {
            assertThat(RideTransitions.removeIfTerminal(ride(RideStatus.CANCELLED, null)))
                    .isEqualTo(RideChange.remove());
            assertThat(RideTransitions.removeIfTerminal(ride(RideStatus.TRIP_COMPLETED, "d1")))
                    .isEqualTo(RideChange.remove());
        }

        // No pisa una solicitud nueva creada en la misma clave mientras tanto.
        @Test
        void no_borra_una_carrera_que_volvio_a_estar_activa() {
            assertThat(RideTransitions.removeIfTerminal(ride(RideStatus.PENDING, null)))
                    .isEqualTo(RideChange.unchanged());
            assertThat(RideTransitions.removeIfTerminal(ride(RideStatus.TRIP_STARTED, "d1")))
                    .isEqualTo(RideChange.unchanged());
        }
    }
}
