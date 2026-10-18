package com.areascript.taxiapp.ride.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoDistanceTest {

    @Test
    void el_mismo_punto_esta_a_cero_metros() {
        assertThat(GeoDistance.metersBetween(-1.6635, -78.6546, -1.6635, -78.6546)).isZero();
    }

    // Un grado de latitud son ~111,2 km.
    @Test
    void un_grado_de_latitud_son_unos_111_kilometros() {
        double meters = GeoDistance.metersBetween(0, 0, 1, 0);

        assertThat(meters).isBetween(111_000.0, 111_400.0);
    }

    @Test
    void la_distancia_es_simetrica() {
        double ida = GeoDistance.metersBetween(-1.67, -78.65, -1.6635, -78.6546);
        double vuelta = GeoDistance.metersBetween(-1.6635, -78.6546, -1.67, -78.65);

        assertThat(ida).isEqualTo(vuelta);
    }

    // El caso real de los tests de caracterización: unos 880 m en Riobamba.
    @Test
    void entre_dos_puntos_de_riobamba_son_unos_880_metros() {
        assertThat(GeoDistance.metersBetween(-1.67, -78.65, -1.6635, -78.6546)).isBetween(850.0, 910.0);
    }
}
