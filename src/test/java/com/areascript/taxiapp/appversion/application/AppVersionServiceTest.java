package com.areascript.taxiapp.appversion.application;

import com.areascript.taxiapp.appversion.application.AppVersionService.VersionCheck;
import com.areascript.taxiapp.appversion.domain.AppVersion;
import com.areascript.taxiapp.appversion.domain.VersionPolicies;
import com.areascript.taxiapp.appversion.domain.VersionPolicy;
import com.areascript.taxiapp.common.error.InvalidInputException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppVersionServiceTest {

    private static final String STORE = "https://play.google.com/store/apps/details?id=com.areascript.driver_app";

    private AppVersionService service;

    @BeforeEach
    void setUp() {
        Map<String, VersionPolicy> configured = Map.of(
                "driver", new VersionPolicy(AppVersion.parse("1.0.7").orElseThrow(), STORE));
        VersionPolicies policies = app -> Optional.ofNullable(configured.get(app));
        service = new AppVersionService(policies);
    }

    @Test
    void una_version_por_debajo_de_la_minima_requiere_actualizar() {
        VersionCheck check = service.check("driver", "1.0.6");

        assertThat(check.updateRequired()).isTrue();
        assertThat(check.minimumVersion()).isEqualTo("1.0.7");
        assertThat(check.storeUrl()).isEqualTo(STORE);
    }

    @Test
    void la_version_minima_exacta_puede_seguir() {
        assertThat(service.check("driver", "1.0.7").updateRequired()).isFalse();
    }

    @Test
    void una_version_posterior_puede_seguir() {
        assertThat(service.check("driver", "1.1.0").updateRequired()).isFalse();
        assertThat(service.check("driver", "2.0.0").updateRequired()).isFalse();
    }

    // El sufijo de flavor y el build no cambian la decisión.
    @Test
    void ignora_sufijo_y_build_number_de_la_version_reportada() {
        assertThat(service.check("driver", "1.0.6-dev+18").updateRequired()).isTrue();
        assertThat(service.check("driver", "1.0.7-dev+19").updateRequired()).isFalse();
    }

    // El servidor solo corta el paso cuando está SEGURO de que es vieja.
    @Test
    void una_version_que_no_se_puede_interpretar_no_bloquea() {
        assertThat(service.check("driver", "no-es-version").updateRequired()).isFalse();
        assertThat(service.check("driver", "").updateRequired()).isFalse();
    }

    @Test
    void la_respuesta_siempre_trae_la_minima_y_la_tienda() {
        VersionCheck check = service.check("driver", "9.9.9");

        assertThat(check.updateRequired()).isFalse();
        assertThat(check.minimumVersion()).isEqualTo("1.0.7");
        assertThat(check.storeUrl()).isEqualTo(STORE);
    }

    @Test
    void una_app_desconocida_es_un_error_de_entrada() {
        assertThatThrownBy(() -> service.check("camion", "1.0.0"))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("camion");
    }
}
