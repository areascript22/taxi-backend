package com.areascript.taxiapp.appversion.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionTest {

    private static AppVersion v(String raw) {
        return AppVersion.parse(raw).orElseThrow();
    }

    // Lo que reportan las apps: versionName con sufijo de flavor, o con build.
    @Test
    void ignora_el_sufijo_de_flavor_y_el_build_number() {
        assertThat(v("1.0.7-dev+19")).hasToString("1.0.7");
        assertThat(v("1.0.7+19")).hasToString("1.0.7");
        assertThat(v("1.0.7-dev")).hasToString("1.0.7");
        assertThat(v("1.0.7")).hasToString("1.0.7");
    }

    @Test
    void ignora_espacios_al_inicio() {
        assertThat(v("  2.1.0")).hasToString("2.1.0");
    }

    @Test
    void un_valor_que_no_es_una_version_no_se_parsea() {
        assertThat(AppVersion.parse(null)).isEmpty();
        assertThat(AppVersion.parse("")).isEmpty();
        assertThat(AppVersion.parse("   ")).isEmpty();
        assertThat(AppVersion.parse("abc")).isEmpty();
        assertThat(AppVersion.parse("v1.0.0")).isEmpty();
        assertThat(AppVersion.parse("-1.0")).isEmpty();
    }

    @Test
    void un_segmento_absurdamente_largo_no_es_una_version() {
        assertThat(AppVersion.parse("1.99999999999999999999.0")).isEmpty();
    }

    // El error clásico de comparar texto: "1.10.0" < "1.9.0" como String.
    @Test
    void compara_numericamente_por_segmentos() {
        assertThat(v("1.10.0").compareTo(v("1.9.0"))).isPositive();
        assertThat(v("1.0.7").compareTo(v("1.0.10"))).isNegative();
        assertThat(v("2.0.0").compareTo(v("1.99.99"))).isPositive();
    }

    @Test
    void un_segmento_ausente_cuenta_como_cero() {
        assertThat(v("1.0").compareTo(v("1.0.0"))).isZero();
        assertThat(v("1").compareTo(v("1.0.0"))).isZero();
        assertThat(v("1.0.1").compareTo(v("1.0"))).isPositive();
    }

    @Test
    void isOlderThan_es_estricto() {
        assertThat(v("1.0.6").isOlderThan(v("1.0.7"))).isTrue();
        assertThat(v("1.0.7").isOlderThan(v("1.0.7"))).isFalse();
        assertThat(v("1.0.8").isOlderThan(v("1.0.7"))).isFalse();
    }
}
