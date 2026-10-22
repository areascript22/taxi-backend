package com.areascript.taxiapp.characterization;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/admin/dispatch/config: el panel de configuración del despacho (anillos,
// frescura de la ubicación, historial y el interruptor "los admins pueden
// editar").
//
// Ver: cualquier admin. Editar: el superuser, o un admin si el superuser lo
// habilitó. El interruptor: solo el superuser (o el fundador).
class DispatchConfigCharacterizationTest extends CharacterizationTestBase {

    private static final String URL = "/api/admin/dispatch/config";

    private static String body(long version, String rings, int maxAge) {
        return "{\"rings\":" + rings + ",\"locationMaxAgeSeconds\":" + maxAge + ",\"expectedVersion\":" + version + "}";
    }

    private static final String TWO_RINGS =
            "[{\"radiusMeters\":2000,\"waitSeconds\":8,\"maxDrivers\":5},"
                    + "{\"radiusMeters\":5000,\"waitSeconds\":12,\"maxDrivers\":7}]";

    @Nested
    class Autorizacion {

        @Test
        void sin_token_es_401() throws Exception {
            mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        }

        @Test
        void un_usuario_normal_recibe_403_en_todo() throws Exception {
            String token = userToken("u1");

            mvc.perform(get(URL).header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(get(URL + "/history").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(put(URL).header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(body(0, TWO_RINGS, 45)))
                    .andExpect(status().isForbidden());
            mvc.perform(put(URL + "/admin-editing").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void un_admin_no_puede_mover_el_interruptor() throws Exception {
            String token = adminToken("a1");

            mvc.perform(put(URL + "/admin-editing").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                    .andExpect(status().isForbidden());

            mvc.perform(get(URL).header("Authorization", superUserToken("s1")))
                    .andExpect(jsonPath("$.adminsCanEdit").value(false));
        }

        @Test
        void el_fundador_mueve_el_interruptor_aunque_todavia_no_tenga_el_rol() throws Exception {
            // Mismo criterio que cambiar roles: el fundador se reconoce por uid y
            // correo de la configuración, no por el rol guardado.
            String token = token(FOUNDER_UID, FOUNDER_EMAIL, "Fundador", null, java.util.Map.of());

            mvc.perform(put(URL + "/admin-editing").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.adminsCanEdit").value(true));
        }
    }

    @Nested
    class Ver {

        @Test
        void un_admin_ve_la_configuracion_inicial_y_lo_que_puede_hacer() throws Exception {
            mvc.perform(get(URL).header("Authorization", adminToken("a1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rings", hasSize(3)))
                    .andExpect(jsonPath("$.rings[0].number").value(1))
                    .andExpect(jsonPath("$.rings[0].radiusMeters").value(3000))
                    .andExpect(jsonPath("$.rings[0].waitSeconds").value(10))
                    .andExpect(jsonPath("$.rings[0].maxDrivers").value(10))
                    .andExpect(jsonPath("$.locationMaxAgeSeconds").value(45))
                    .andExpect(jsonPath("$.adminsCanEdit").value(false))
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.totalWaitSeconds").value(30))
                    .andExpect(jsonPath("$.requestExpirySeconds").value(35))
                    .andExpect(jsonPath("$.canEdit").value(false))
                    .andExpect(jsonPath("$.canChangeAdminEditing").value(false))
                    .andExpect(jsonPath("$.limits.maxRings").value(6))
                    .andExpect(jsonPath("$.limits.maxTotalWaitSeconds").value(120))
                    .andExpect(jsonPath("$.limits.minLocationMaxAgeSeconds").value(30))
                    .andExpect(jsonPath("$.limits.minRadiusMeters").value(100))
                    .andExpect(jsonPath("$.limits.maxRadiusMeters").value(50000));
        }

        @Test
        void el_superuser_ve_que_puede_editar_y_mover_el_interruptor() throws Exception {
            mvc.perform(get(URL).header("Authorization", superUserToken("s1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.canEdit").value(true))
                    .andExpect(jsonPath("$.canChangeAdminEditing").value(true));
        }

        @Test
        void un_admin_ve_que_puede_editar_cuando_el_interruptor_esta_encendido() throws Exception {
            dispatchConfig.seedAdminsCanEdit(true);

            mvc.perform(get(URL).header("Authorization", adminToken("a1")))
                    .andExpect(jsonPath("$.canEdit").value(true))
                    .andExpect(jsonPath("$.canChangeAdminEditing").value(false));
        }
    }

    @Nested
    class Editar {

        @Test
        void el_superuser_guarda_y_la_respuesta_trae_la_version_nueva_y_quien_edito() throws Exception {
            mvc.perform(put(URL).header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON).content(body(0, TWO_RINGS, 60)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rings", hasSize(2)))
                    .andExpect(jsonPath("$.rings[1].radiusMeters").value(5000))
                    .andExpect(jsonPath("$.locationMaxAgeSeconds").value(60))
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.updatedByName").value("Super s1"))
                    .andExpect(jsonPath("$.totalWaitSeconds").value(20));

            // y lo que el despacho lee es lo guardado
            mvc.perform(get(URL).header("Authorization", adminToken("a1")))
                    .andExpect(jsonPath("$.rings", hasSize(2)));
        }

        @Test
        void un_admin_recibe_403_con_el_interruptor_apagado_y_nada_cambia() throws Exception {
            mvc.perform(put(URL).header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content(body(0, TWO_RINGS, 60)))
                    .andExpect(status().isForbidden());

            mvc.perform(get(URL).header("Authorization", adminToken("a1")))
                    .andExpect(jsonPath("$.rings", hasSize(3)))
                    .andExpect(jsonPath("$.version").value(0));
        }

        @Test
        void un_admin_guarda_cuando_el_superuser_encendio_el_interruptor() throws Exception {
            dispatchConfig.seedAdminsCanEdit(true);

            mvc.perform(put(URL).header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content(body(0, TWO_RINGS, 60)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updatedByName").value("Admin a1"));
        }

        @Test
        void apagar_el_interruptor_le_quita_al_admin_la_edicion_en_el_siguiente_request() throws Exception {
            String admin = adminToken("a1");
            String superUser = superUserToken("s1");
            mvc.perform(put(URL + "/admin-editing").header("Authorization", superUser)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk());
            mvc.perform(put(URL).header("Authorization", admin)
                    .contentType(MediaType.APPLICATION_JSON).content(body(0, TWO_RINGS, 60))).andExpect(status().isOk());

            mvc.perform(put(URL + "/admin-editing").header("Authorization", superUser)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());

            mvc.perform(put(URL).header("Authorization", admin)
                    .contentType(MediaType.APPLICATION_JSON).content(body(1, TWO_RINGS, 50)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void valores_invalidos_son_400_con_un_mensaje_que_se_puede_corregir() throws Exception {
            String flat = "[{\"radiusMeters\":3000,\"waitSeconds\":10,\"maxDrivers\":10},"
                    + "{\"radiusMeters\":3000,\"waitSeconds\":10,\"maxDrivers\":10}]";

            mvc.perform(put(URL).header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON).content(body(0, flat, 45)))
                    .andExpect(status().isBadRequest());
            mvc.perform(put(URL).header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON).content(body(0, "[]", 45)))
                    .andExpect(status().isBadRequest());
            mvc.perform(put(URL).header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON).content(body(0, TWO_RINGS, 10)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void faltan_campos_es_400() throws Exception {
            mvc.perform(put(URL).header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"rings\":[]}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(put(URL).header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(0, "[{\"radiusMeters\":3000}]", 45)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void una_version_vieja_es_409_y_no_pisa_el_cambio_de_otro() throws Exception {
            String superUser = superUserToken("s1");
            mvc.perform(put(URL).header("Authorization", superUser)
                    .contentType(MediaType.APPLICATION_JSON).content(body(0, TWO_RINGS, 60))).andExpect(status().isOk());

            String other = "[{\"radiusMeters\":9000,\"waitSeconds\":10,\"maxDrivers\":10}]";
            mvc.perform(put(URL).header("Authorization", superUser)
                            .contentType(MediaType.APPLICATION_JSON).content(body(0, other, 60)))
                    .andExpect(status().isConflict());

            mvc.perform(get(URL).header("Authorization", superUser))
                    .andExpect(jsonPath("$.rings", hasSize(2)))
                    .andExpect(jsonPath("$.version").value(1));
        }
    }

    @Nested
    class Historial {

        @Test
        void cada_cambio_queda_con_autor_y_foto_de_antes_y_despues() throws Exception {
            String superUser = superUserToken("s1");
            mvc.perform(put(URL).header("Authorization", superUser)
                    .contentType(MediaType.APPLICATION_JSON).content(body(0, TWO_RINGS, 60))).andExpect(status().isOk());
            mvc.perform(put(URL + "/admin-editing").header("Authorization", superUser)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk());

            mvc.perform(get(URL + "/history").header("Authorization", adminToken("a1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changes", hasSize(2)))
                    .andExpect(jsonPath("$.changes[*].type", contains("admins_can_edit", "config")))
                    .andExpect(jsonPath("$.changes[1].changedByName").value("Super s1"))
                    .andExpect(jsonPath("$.changes[1].before.rings", hasSize(3)))
                    .andExpect(jsonPath("$.changes[1].after.rings", hasSize(2)))
                    .andExpect(jsonPath("$.changes[1].after.locationMaxAgeSeconds").value(60))
                    .andExpect(jsonPath("$.changes[0].before.adminsCanEdit").value(false))
                    .andExpect(jsonPath("$.changes[0].after.adminsCanEdit").value(true));
        }

        @Test
        void el_limite_se_valida_y_pagina() throws Exception {
            String superUser = superUserToken("s1");
            mvc.perform(put(URL).header("Authorization", superUser)
                    .contentType(MediaType.APPLICATION_JSON).content(body(0, TWO_RINGS, 60))).andExpect(status().isOk());
            mvc.perform(put(URL).header("Authorization", superUser)
                    .contentType(MediaType.APPLICATION_JSON).content(body(1, TWO_RINGS, 70))).andExpect(status().isOk());

            mvc.perform(get(URL + "/history?limit=1").header("Authorization", superUser))
                    .andExpect(jsonPath("$.changes", hasSize(1)))
                    .andExpect(jsonPath("$.nextBeforeId").value(2));
            mvc.perform(get(URL + "/history?limit=1&beforeId=2").header("Authorization", superUser))
                    .andExpect(jsonPath("$.changes", hasSize(1)))
                    .andExpect(jsonPath("$.changes[0].id").value(1));
            mvc.perform(get(URL + "/history?limit=0").header("Authorization", superUser))
                    .andExpect(status().isBadRequest());
        }
    }
}
