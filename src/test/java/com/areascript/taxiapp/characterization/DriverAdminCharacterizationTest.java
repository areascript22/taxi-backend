package com.areascript.taxiapp.characterization;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.areascript.taxiapp.user.domain.UserRole;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.AuthErrorCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/drivers/**: panel de administración de conductores (driver_app).
//
// Jerarquía que aplican todas las acciones de moderación: nadie actúa sobre un
// superuser, y un admin no actúa sobre otro admin (un superuser sí).
//
// FOUNDER_UID/FOUNDER_EMAIL vienen de CharacterizationTestBase: desde la fase 1
// el fundador es configuración (taxiapp.admin.*), ya no una constante en código.
class DriverAdminCharacterizationTest extends CharacterizationTestBase {

    private void storedDriver(String uid, Map<String, Object> extra) {
        Map<String, Object> data = new HashMap<>();
        data.put("firstName", "Nombre-" + uid);
        data.put("lastName", "Apellido-" + uid);
        data.put("email", uid + "@test.com");
        data.put("phoneNumber", "0990000000");
        data.put("fcmToken", "fcm-" + uid);
        data.putAll(extra);
        firestore.put("drivers/" + uid, data);
    }

    // ---- Autorización ----

    @Nested
    class Autorizacion {

        @Test
        void un_usuario_sin_claim_admin_recibe_403_en_todo() throws Exception {
            String token = userToken("u1");
            storedDriver("d1", Map.of());

            mvc.perform(get("/api/drivers").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/drivers/d1").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/drivers/d1").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/drivers/d1/approval-status").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/drivers/d1/block-status").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"blocked\":true,\"reason\":\"x\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/drivers/d1/role").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                    .andExpect(status().isForbidden());

            assertThat(firestore.get("drivers/d1")).doesNotContainKeys("approvalStatus", "isBlocked", "role");
        }

        // Cambiar roles exige superuser: un admin normal no puede.
        @Test
        void un_admin_no_puede_cambiar_roles() throws Exception {
            storedDriver("d1", Map.of());
            authUser("d1", Map.of());

            mvc.perform(put("/api/drivers/d1/role").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                    .andExpect(status().isForbidden());

            verify(firebaseAuth, never()).setCustomUserClaims(anyString(), any());
        }

        @Test
        void sin_token_responde_401() throws Exception {
            mvc.perform(get("/api/drivers"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ---- Listado y detalle ----

    @Nested
    class Listado {

        @Test
        void sin_pageSize_devuelve_todos_los_conductores_con_su_vehiculo() throws Exception {
            storedDriver("d1", Map.of("vehicleId", "v1", "rating", 4.5, "role", "admin",
                    "approvalStatus", "pending", "isBlocked", false,
                    "createdAt", Timestamp.ofTimeSecondsAndNanos(1_700_000_000L, 0)));
            vehicle("v1", "d1");
            storedDriver("d2", Map.of());

            mvc.perform(get("/api/drivers").header("Authorization", adminToken("a1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.drivers", hasSize(2)))
                    .andExpect(jsonPath("$.hasMore").value(false))
                    .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                    .andExpect(jsonPath("$.drivers[0].uid").value("d1"))
                    .andExpect(jsonPath("$.drivers[0].firstName").value("Nombre-d1"))
                    .andExpect(jsonPath("$.drivers[0].lastName").value("Apellido-d1"))
                    .andExpect(jsonPath("$.drivers[0].email").value("d1@test.com"))
                    .andExpect(jsonPath("$.drivers[0].phoneNumber").value("0990000000"))
                    .andExpect(jsonPath("$.drivers[0].fcmToken").value("fcm-d1"))
                    .andExpect(jsonPath("$.drivers[0].rating").value(4.5))
                    .andExpect(jsonPath("$.drivers[0].role").value("admin"))
                    .andExpect(jsonPath("$.drivers[0].approvalStatus").value("pending"))
                    .andExpect(jsonPath("$.drivers[0].isBlocked").value(false))
                    .andExpect(jsonPath("$.drivers[0].createdAt").value("2023-11-14T22:13:20Z"))
                    .andExpect(jsonPath("$.drivers[0].vehicle.vehicleId").value("v1"))
                    .andExpect(jsonPath("$.drivers[0].vehicle.driverId").value("d1"))
                    .andExpect(jsonPath("$.drivers[0].vehicle.plate").value("PBX-1234"))
                    .andExpect(jsonPath("$.drivers[0].vehicle.brand").value("Toyota"))
                    .andExpect(jsonPath("$.drivers[0].vehicle.model").value("Corolla"))
                    .andExpect(jsonPath("$.drivers[0].vehicle.year").value(2020))
                    .andExpect(jsonPath("$.drivers[0].vehicle.color").value("Amarillo"))
                    .andExpect(jsonPath("$.drivers[0].vehicle.verificationStatus").value("pending"))
                    .andExpect(jsonPath("$.drivers[1].vehicle").value(nullValue()));
        }

        // Documentos viejos sin los campos nuevos: el servidor rellena defaults.
        @Test
        void aplica_defaults_a_los_campos_que_faltan() throws Exception {
            firestore.put("drivers/d1", Map.of());

            mvc.perform(get("/api/drivers/d1").header("Authorization", adminToken("a1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.uid").value("d1"))
                    .andExpect(jsonPath("$.firstName").value(""))
                    .andExpect(jsonPath("$.photoUrl").value(nullValue()))
                    .andExpect(jsonPath("$.rating").value(5.0))
                    .andExpect(jsonPath("$.role").value("driver"))
                    .andExpect(jsonPath("$.approvalStatus").value("approved"))
                    .andExpect(jsonPath("$.isBlocked").value(false))
                    .andExpect(jsonPath("$.blockReason").value(nullValue()))
                    .andExpect(jsonPath("$.createdAt").value(nullValue()))
                    .andExpect(jsonPath("$.vehicle").value(nullValue()));
        }

        @Test
        void pagina_por_cursor_ordenando_por_id() throws Exception {
            storedDriver("d1", Map.of());
            storedDriver("d2", Map.of());
            storedDriver("d3", Map.of());

            mvc.perform(get("/api/drivers").param("pageSize", "2").header("Authorization", adminToken("a1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.drivers[*].uid", contains("d1", "d2")))
                    .andExpect(jsonPath("$.hasMore").value(true))
                    .andExpect(jsonPath("$.nextCursor").value("d2"));

            mvc.perform(get("/api/drivers").param("pageSize", "2").param("cursor", "d2")
                            .header("Authorization", adminToken("a1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.drivers[*].uid", contains("d3")))
                    .andExpect(jsonPath("$.hasMore").value(false))
                    .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        }

        @Test
        void el_pageSize_se_limita_entre_1_y_100() throws Exception {
            storedDriver("d1", Map.of());
            storedDriver("d2", Map.of());

            mvc.perform(get("/api/drivers").param("pageSize", "0").header("Authorization", adminToken("a1")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.drivers", hasSize(1)))
                    .andExpect(jsonPath("$.hasMore").value(true));
        }

        @Test
        void detalle_de_un_conductor_inexistente_responde_404() throws Exception {
            mvc.perform(get("/api/drivers/nadie").header("Authorization", adminToken("a1")))
                    .andExpect(status().isNotFound());
        }
    }

    // ---- Eliminación ----

    @Nested
    class Eliminacion {

        @Test
        void un_admin_elimina_a_un_conductor_y_limpia_sus_datos() throws Exception {
            storedDriver("d1", Map.of("vehicleId", "v1"));
            vehicle("v1", "d1");
            authUser("d1", Map.of());

            mvc.perform(delete("/api/drivers/d1").header("Authorization", adminToken("a1")))
                    .andExpect(status().isNoContent());

            verify(firebaseAuth).deleteUser("d1");
            assertThat(firestore.exists("drivers/d1")).isFalse();
            assertThat(firestore.exists("vehicles/v1")).isFalse();
        }

        @Test
        void nadie_puede_eliminar_a_un_superuser() throws Exception {
            storedDriver("s1", Map.of());
            authUser("s1", Map.of("admin", true, "superuser", true));

            mvc.perform(delete("/api/drivers/s1").header("Authorization", superUserToken("s2")))
                    .andExpect(status().isForbidden());

            verify(firebaseAuth, never()).deleteUser(anyString());
        }

        @Test
        void un_admin_no_puede_eliminar_a_otro_admin() throws Exception {
            storedDriver("a2", Map.of());
            authUser("a2", Map.of("admin", true));

            mvc.perform(delete("/api/drivers/a2").header("Authorization", adminToken("a1")))
                    .andExpect(status().isForbidden());

            verify(firebaseAuth, never()).deleteUser(anyString());
        }

        @Test
        void un_superuser_si_puede_eliminar_a_un_admin() throws Exception {
            storedDriver("a2", Map.of());
            authUser("a2", Map.of("admin", true));

            mvc.perform(delete("/api/drivers/a2").header("Authorization", superUserToken("s1")))
                    .andExpect(status().isNoContent());

            verify(firebaseAuth).deleteUser("a2");
        }

        @Test
        void eliminar_un_usuario_inexistente_en_auth_responde_404() throws Exception {
            doThrow(authError(AuthErrorCode.USER_NOT_FOUND)).when(firebaseAuth).getUser("nadie");

            mvc.perform(delete("/api/drivers/nadie").header("Authorization", adminToken("a1")))
                    .andExpect(status().isNotFound());
        }
    }

    // ---- Cambio de rol ----

    @Nested
    class Rol {

        @Test
        void un_superuser_promueve_a_admin_en_la_base() throws Exception {
            storedDriver("d1", Map.of());
            authUser("d1", Map.of());

            mvc.perform(put("/api/drivers/d1/role").header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                    .andExpect(status().isNoContent());

            assertThat(users.roleOf("d1")).isEqualTo(UserRole.ADMIN);
            assertThat(firestore.get("drivers/d1")).containsEntry("role", "admin");
        }

        @Test
        void un_superuser_degrada_a_driver() throws Exception {
            storedDriver("a1", Map.of("role", "admin"));
            authUser("a1", Map.of("admin", true));

            mvc.perform(put("/api/drivers/a1/role").header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"driver\"}"))
                    .andExpect(status().isNoContent());

            assertThat(users.roleOf("a1")).isEqualTo(UserRole.USER);
            assertThat(firestore.get("drivers/a1")).containsEntry("role", "driver");
        }

        @Test
        void nadie_puede_promover_a_superuser() throws Exception {
            storedDriver("d1", Map.of());
            authUser("d1", Map.of());

            mvc.perform(put("/api/drivers/d1/role").header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"superuser\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void un_rol_desconocido_responde_400() throws Exception {
            mvc.perform(put("/api/drivers/d1/role").header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"jefe\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void no_se_puede_cambiar_el_rol_del_fundador() throws Exception {
            mvc.perform(put("/api/drivers/" + FOUNDER_UID + "/role").header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"driver\"}"))
                    .andExpect(status().isForbidden());

            verify(firebaseAuth, never()).setCustomUserClaims(anyString(), any());
        }

        // El fundador puede cambiar roles aunque todavía no tenga el claim de
        // superuser, siempre que coincidan uid Y email del token.
        @Test
        void el_fundador_sin_claims_puede_cambiar_roles() throws Exception {
            storedDriver("d1", Map.of());
            authUser("d1", Map.of());
            String founderToken = token(FOUNDER_UID, FOUNDER_EMAIL, "Fundador", null, Map.of());

            mvc.perform(put("/api/drivers/d1/role").header("Authorization", founderToken)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                    .andExpect(status().isNoContent());
        }

        @Test
        void el_uid_del_fundador_con_otro_email_no_alcanza() throws Exception {
            storedDriver("d1", Map.of());
            authUser("d1", Map.of());
            String impostor = token(FOUNDER_UID, "otro@test.com", "Impostor", null, Map.of());

            mvc.perform(put("/api/drivers/d1/role").header("Authorization", impostor)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void no_se_puede_cambiar_el_rol_de_otro_superuser() throws Exception {
            authUser("s2", Map.of("admin", true, "superuser", true));

            mvc.perform(put("/api/drivers/s2/role").header("Authorization", superUserToken("s1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"driver\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ---- Aprobación ----

    @Nested
    class Aprobacion {

        @Test
        void aprobar_guarda_quien_y_cuando_y_borra_el_motivo_de_rechazo() throws Exception {
            storedDriver("d1", Map.of("approvalStatus", "rejected", "rejectionReason", "fotos borrosas"));
            authUser("d1", Map.of());

            mvc.perform(put("/api/drivers/d1/approval-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                    .andExpect(status().isNoContent());

            Map<String, Object> driver = firestore.get("drivers/d1");
            assertThat(driver).containsEntry("approvalStatus", "approved")
                    .containsEntry("approvedBy", "a1")
                    .doesNotContainKey("rejectionReason");
            assertThat(driver.get("approvedAt")).isInstanceOf(Timestamp.class);
        }

        @Test
        void rechazar_exige_motivo() throws Exception {
            storedDriver("d1", Map.of());
            authUser("d1", Map.of());

            mvc.perform(put("/api/drivers/d1/approval-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"rejected\",\"reason\":\"  \"}"))
                    .andExpect(status().isBadRequest());

            mvc.perform(put("/api/drivers/d1/approval-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"rejected\",\"reason\":\"sin licencia\"}"))
                    .andExpect(status().isNoContent());

            assertThat(firestore.get("drivers/d1")).containsEntry("approvalStatus", "rejected")
                    .containsEntry("rejectionReason", "sin licencia")
                    .containsEntry("rejectedBy", "a1")
                    .containsKey("rejectedAt");
        }

        @Test
        void volver_a_pending_solo_cambia_el_estado() throws Exception {
            storedDriver("d1", Map.of("approvalStatus", "approved"));
            authUser("d1", Map.of());

            mvc.perform(put("/api/drivers/d1/approval-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"pending\"}"))
                    .andExpect(status().isNoContent());

            assertThat(firestore.get("drivers/d1")).containsEntry("approvalStatus", "pending");
        }

        @Test
        void un_estado_desconocido_responde_400() throws Exception {
            mvc.perform(put("/api/drivers/d1/approval-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"quizas\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void un_conductor_inexistente_responde_404() throws Exception {
            mvc.perform(put("/api/drivers/nadie/approval-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void un_admin_no_puede_aprobar_a_otro_admin() throws Exception {
            storedDriver("a2", Map.of("approvalStatus", "pending"));
            authUser("a2", Map.of("admin", true));

            mvc.perform(put("/api/drivers/a2/approval-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"approved\"}"))
                    .andExpect(status().isForbidden());

            assertThat(firestore.get("drivers/a2")).containsEntry("approvalStatus", "pending");
        }
    }

    // ---- Bloqueo ----

    @Nested
    class Bloqueo {

        @Test
        void bloquear_guarda_motivo_quien_y_cuando() throws Exception {
            storedDriver("d1", Map.of("approvalStatus", "approved"));
            authUser("d1", Map.of());

            mvc.perform(put("/api/drivers/d1/block-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"blocked\":true,\"reason\":\"quejas\"}"))
                    .andExpect(status().isNoContent());

            Map<String, Object> driver = firestore.get("drivers/d1");
            assertThat(driver).containsEntry("isBlocked", true)
                    .containsEntry("blockReason", "quejas")
                    .containsEntry("blockedBy", "a1");
            assertThat(driver.get("blockedAt")).isInstanceOf(Timestamp.class);
        }

        // Un documento sin approvalStatus cuenta como aprobado.
        @Test
        void un_conductor_sin_approvalStatus_se_puede_bloquear() throws Exception {
            storedDriver("d1", Map.of());
            authUser("d1", Map.of());

            mvc.perform(put("/api/drivers/d1/block-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"blocked\":true,\"reason\":\"quejas\"}"))
                    .andExpect(status().isNoContent());
        }

        @Test
        void desbloquear_borra_los_datos_del_bloqueo() throws Exception {
            storedDriver("d1", Map.of("approvalStatus", "approved", "isBlocked", true,
                    "blockReason", "quejas", "blockedBy", "a1", "blockedAt", Timestamp.now()));
            authUser("d1", Map.of());

            mvc.perform(put("/api/drivers/d1/block-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"blocked\":false}"))
                    .andExpect(status().isNoContent());

            assertThat(firestore.get("drivers/d1")).containsEntry("isBlocked", false)
                    .doesNotContainKeys("blockReason", "blockedBy", "blockedAt");
        }

        @Test
        void bloquear_exige_motivo() throws Exception {
            storedDriver("d1", Map.of("approvalStatus", "approved"));

            mvc.perform(put("/api/drivers/d1/block-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"blocked\":true}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void no_se_puede_bloquear_a_un_conductor_no_aprobado() throws Exception {
            storedDriver("d1", Map.of("approvalStatus", "pending"));
            authUser("d1", Map.of());

            mvc.perform(put("/api/drivers/d1/block-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"blocked\":true,\"reason\":\"quejas\"}"))
                    .andExpect(status().isConflict());

            assertThat(firestore.get("drivers/d1")).doesNotContainKey("isBlocked");
        }

        @Test
        void un_admin_no_puede_desbloquear_a_otro_admin() throws Exception {
            storedDriver("a2", Map.of("approvalStatus", "approved", "isBlocked", true));
            authUser("a2", Map.of("admin", true));

            mvc.perform(put("/api/drivers/a2/block-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"blocked\":false}"))
                    .andExpect(status().isForbidden());

            assertThat(firestore.get("drivers/a2")).containsEntry("isBlocked", true);
        }

        @Test
        void un_conductor_inexistente_responde_404() throws Exception {
            mvc.perform(put("/api/drivers/nadie/block-status").header("Authorization", adminToken("a1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"blocked\":false}"))
                    .andExpect(status().isNotFound());
        }
    }
}
