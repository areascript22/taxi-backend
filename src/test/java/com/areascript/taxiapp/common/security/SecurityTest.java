package com.areascript.taxiapp.common.security;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.areascript.taxiapp.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Cadena de seguridad (SecurityConfig + FirebaseTokenAuthenticationFilter): lo
// que ve el cliente cuando no hay token o no alcanza el rol. Los códigos por
// endpoint ya los cubren los tests de caracterización; acá va el cuerpo y las
// reglas de la cadena en sí.
class SecurityTest extends CharacterizationTestBase {

    // ---- 401 ----

    @Test
    void sin_token_responde_401_con_problem_detail() throws Exception {
        mvc.perform(get("/api/rides/passenger/active"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void un_token_invalido_responde_401() throws Exception {
        mvc.perform(get("/api/drivers").header("Authorization", "Bearer basura"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // El filtro cubre todo /api/**, también rutas que no existen: 401, no 404.
    @Test
    void una_ruta_inexistente_de_api_sin_token_responde_401() throws Exception {
        mvc.perform(get("/api/no-existe"))
                .andExpect(status().isUnauthorized());
    }

    // ---- 403 ----

    @Test
    void sin_el_rol_necesario_responde_403_con_problem_detail() throws Exception {
        mvc.perform(get("/api/drivers").header("Authorization", userToken("u1")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    // Un admin sin ser superuser ni fundador no puede cambiar roles.
    @Test
    void cambiar_roles_exige_superuser_o_fundador() throws Exception {
        mvc.perform(put("/api/drivers/d1/role").header("Authorization", adminToken("a1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // El rol guardado en la base decide, no los claims del token. Un superuser
    // incluye al admin (antes eran dos claims independientes, pero nadie era
    // superuser sin ser admin: el arranque y el endpoint de roles siempre los
    // asignaban juntos).
    @Test
    void el_rol_superuser_guardado_en_la_base_abre_el_panel() throws Exception {
        String token = token("s1", "s1@test.com", "Super", null, Map.of("superuser", true));

        mvc.perform(get("/api/drivers").header("Authorization", token))
                .andExpect(status().isOk());
    }

    // Y un claim de admin que la base no respalda no abre nada.
    @Test
    void un_claim_de_admin_que_la_base_no_respalda_no_abre_el_panel() throws Exception {
        String token = adminToken("a1");
        users.seed("a1", "a1@test.com", "Admin", UserRole.USER);

        mvc.perform(get("/api/drivers").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void el_fundador_sin_claims_no_abre_el_panel_solo_cambia_roles() throws Exception {
        String founder = token(FOUNDER_UID, FOUNDER_EMAIL, "Fundador", null, Map.of());

        mvc.perform(get("/api/drivers").header("Authorization", founder))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/drivers/d1").header("Authorization", founder))
                .andExpect(status().isForbidden());
    }

    // ---- Lo que no cambia ----

    // Fuera de /api/** no se exige token (como antes de la fase 2).
    @Test
    void rutas_fuera_de_api_no_exigen_token() throws Exception {
        mvc.perform(get("/no-existe"))
                .andExpect(status().isNotFound());
    }

    // Sin estado: ninguna respuesta abre sesión ni manda cookies.
    @Test
    void no_se_crea_sesion_ni_cookie() throws Exception {
        mvc.perform(get("/api/rides/passenger/active").header("Authorization", userToken("p1")))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
