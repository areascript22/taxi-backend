package com.areascript.taxiapp.appversion.api;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /api/public/app-version: público (sin token) y con la política que
// define la configuración. La versión mínima se fija acá para no depender del
// valor por defecto (0.0.0, que no bloquea a nadie).
@TestPropertySource(properties = {
        "taxiapp.app-versions.driver.min-version=1.0.7",
        "taxiapp.app-versions.driver.store-url=https://play.google.com/store/apps/details?id=com.areascript.driver_app",
        "taxiapp.app-versions.passenger.min-version=1.0.4",
        "taxiapp.app-versions.passenger.store-url=https://play.google.com/store/apps/details?id=com.areascript.passenger_app"
})
class AppVersionEndpointTest extends CharacterizationTestBase {

    @Test
    void es_publico_no_pide_token() throws Exception {
        mvc.perform(get("/api/public/app-version").param("app", "driver").param("version", "1.0.7"))
                .andExpect(status().isOk());
    }

    @Test
    void una_version_vieja_requiere_actualizar() throws Exception {
        mvc.perform(get("/api/public/app-version").param("app", "driver").param("version", "1.0.6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateRequired").value(true))
                .andExpect(jsonPath("$.minimumVersion").value("1.0.7"))
                .andExpect(jsonPath("$.storeUrl", containsString("com.areascript.driver_app")));
    }

    @Test
    void una_version_al_dia_puede_seguir() throws Exception {
        mvc.perform(get("/api/public/app-version").param("app", "driver").param("version", "1.0.7-dev+19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateRequired").value(false));
    }

    // Cada app tiene su propia política.
    @Test
    void cada_app_tiene_su_propia_minima() throws Exception {
        mvc.perform(get("/api/public/app-version").param("app", "passenger").param("version", "1.0.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateRequired").value(false))
                .andExpect(jsonPath("$.minimumVersion").value("1.0.4"))
                .andExpect(jsonPath("$.storeUrl", containsString("com.areascript.passenger_app")));
    }

    @Test
    void una_version_ininterpretable_deja_pasar() throws Exception {
        mvc.perform(get("/api/public/app-version").param("app", "driver").param("version", "rara"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateRequired").value(false));
    }

    @Test
    void una_app_desconocida_responde_400() throws Exception {
        mvc.perform(get("/api/public/app-version").param("app", "camion").param("version", "1.0.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sin_parametros_responde_400() throws Exception {
        mvc.perform(get("/api/public/app-version"))
                .andExpect(status().isBadRequest());
    }

    // Lo público es SOLO esto: el resto de /api sigue exigiendo token.
    @Test
    void el_resto_de_la_api_sigue_exigiendo_token() throws Exception {
        mvc.perform(get("/api/rides/passenger/active"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public-pero-no/app-version"))
                .andExpect(status().isUnauthorized());
    }
}
