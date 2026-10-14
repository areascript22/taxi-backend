package com.areascript.taxiapp.characterization;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Filtro de autenticación (FirebaseAuthenticationFilter): todo /api/** exige
// un ID token de Firebase válido en "Authorization: Bearer <token>".
class AuthFilterCharacterizationTest extends CharacterizationTestBase {

    @Test
    void api_sin_header_authorization_responde_401() throws Exception {
        mvc.perform(get("/api/rides/passenger/active"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void api_con_header_que_no_es_bearer_responde_401() throws Exception {
        mvc.perform(get("/api/rides/passenger/active").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void api_con_token_invalido_responde_401() throws Exception {
        mvc.perform(get("/api/rides/passenger/active").header("Authorization", "Bearer no-registrado"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void api_con_token_valido_llega_al_controller() throws Exception {
        mvc.perform(get("/api/rides/passenger/active").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent());
    }

    // Fuera de /api/** el filtro no actúa: una ruta inexistente da 404, no 401.
    @Test
    void rutas_fuera_de_api_no_pasan_por_el_filtro() throws Exception {
        mvc.perform(get("/no-existe"))
                .andExpect(status().isNotFound());
    }
}
