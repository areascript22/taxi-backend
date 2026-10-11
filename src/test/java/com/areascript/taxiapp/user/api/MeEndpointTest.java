package com.areascript.taxiapp.user.api;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.areascript.taxiapp.user.domain.AppProfile;
import com.areascript.taxiapp.user.domain.DriverProfile;
import com.areascript.taxiapp.user.domain.PassengerProfile;
import com.areascript.taxiapp.user.domain.UserRole;
import com.areascript.taxiapp.user.domain.VehicleSummary;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/me y la autorización por el rol guardado, a nivel HTTP: filtro de
// seguridad, controller y casos de uso reales; solo el almacenamiento (base de
// usuarios y Firebase) es de mentira.
class MeEndpointTest extends CharacterizationTestBase {

    private static final String JSON = "application/json";

    private static UUID idOf(String firebaseUid) {
        return UUID.nameUUIDFromBytes(firebaseUid.getBytes());
    }

    // ---- GET /api/me ----

    @Test
    void sin_token_responde_401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void la_primera_vez_crea_a_la_persona_con_rol_user_y_sin_perfiles() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", userToken("nueva")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("nueva"))
                .andExpect(jsonPath("$.role").value("user"))
                .andExpect(jsonPath("$.blocked").value(false))
                .andExpect(jsonPath("$.driver").doesNotExist())
                .andExpect(jsonPath("$.passenger").doesNotExist());

        assertThat(users.exists("nueva")).isTrue();
    }

    @Test
    void devuelve_el_rol_guardado_y_los_perfiles_de_las_dos_apps() throws Exception {
        String token = adminToken("ana");
        userProfiles.putDriver(idOf("ana"), new DriverProfile(
                "0991112222", "https://p/ana.jpg", 4.5, "approved", "es",
                new VehicleSummary("v1", "PBX-1234", "Toyota", "Corolla", 2020, "Amarillo", "REG-1", "pending")));
        userProfiles.putPassenger(idOf("ana"), new PassengerProfile(null, "active", "en"));

        mvc.perform(get("/api/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("admin"))
                .andExpect(jsonPath("$.driver.approvalStatus").value("approved"))
                .andExpect(jsonPath("$.driver.vehicle.plate").value("PBX-1234"))
                .andExpect(jsonPath("$.driver.vehicle.year").value(2020))
                .andExpect(jsonPath("$.passenger.status").value("active"))
                .andExpect(jsonPath("$.passenger.language").value("en"));
    }

    // ---- La base manda sobre los claims del token ----

    // Lo central de este paso: el token PRUEBA quién es; qué puede hacer lo
    // dice la base. Un claim de admin que la base no respalda no sirve.
    @Test
    void un_claim_de_admin_sin_rol_en_la_base_no_da_acceso_de_admin() throws Exception {
        String token = adminToken("falso");        // el token trae admin=true...
        users.seed("falso", "falso@test.com", "Falso", UserRole.USER);   // ...pero la base dice user

        mvc.perform(get("/api/drivers").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    // Y al revés: un cambio de rol en la base vale en el siguiente request, sin
    // esperar a que el token (que dura hasta 1 hora) se renueve.
    @Test
    void un_rol_de_admin_en_la_base_da_acceso_aunque_el_token_no_lo_traiga() throws Exception {
        String token = userToken("promovido");     // el token no trae claims...
        users.seed("promovido", "p@test.com", "P", UserRole.ADMIN);     // ...pero la base dice admin
        firestore.put("drivers/d1", Map.of("firstName", "Uno"));

        mvc.perform(get("/api/drivers").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void cambiar_el_rol_de_otro_queda_guardado_en_la_base_de_usuarios() throws Exception {
        authUser("d1", Map.of());
        firestore.put("drivers/d1", Map.of("firstName", "Uno", "role", "driver"));
        users.seed("d1", "d1@test.com", "Uno", UserRole.USER);

        mvc.perform(put("/api/drivers/d1/role").header("Authorization", superUserToken("s1"))
                        .contentType(JSON).content("{\"role\":\"admin\"}"))
                .andExpect(status().isNoContent());

        assertThat(users.roleOf("d1")).isEqualTo(UserRole.ADMIN);
    }

    // ---- PUT /api/me/device ----

    @Test
    void registra_el_dispositivo_en_la_base_y_en_firestore() throws Exception {
        userProfiles.putDriver(idOf("c1"), new DriverProfile("", null, 5.0, "approved", null, null));
        firestore.put("drivers/c1", Map.of("firstName", "Uno"));

        mvc.perform(put("/api/me/device").header("Authorization", userToken("c1"))
                        .contentType(JSON)
                        .content("{\"app\":\"driver\",\"fcmToken\":\"tok-1\",\"language\":\"en\"}"))
                .andExpect(status().isNoContent());

        assertThat(userProfiles.savedDevices()).hasSize(1);
        assertThat(userProfiles.savedDevices().get(0).app()).isEqualTo(AppProfile.DRIVER);
        assertThat(userProfiles.savedDevices().get(0).fcmToken()).isEqualTo("tok-1");
        assertThat(firestore.get("drivers/c1").get("fcmToken")).isEqualTo("tok-1");
        assertThat(firestore.get("drivers/c1").get("language")).isEqualTo("en");
    }

    // Una persona con las dos apps tiene dos dispositivos: cada app escribe el suyo.
    @Test
    void el_token_de_cada_app_va_a_su_propio_perfil() throws Exception {
        userProfiles.putDriver(idOf("dual"), new DriverProfile("", null, 5.0, "approved", null, null));
        userProfiles.putPassenger(idOf("dual"), new PassengerProfile(null, "active", null));
        firestore.put("drivers/dual", Map.of("firstName", "D"));
        firestore.put("passengers/dual", Map.of("firstName", "P"));
        String token = userToken("dual");

        mvc.perform(put("/api/me/device").header("Authorization", token).contentType(JSON)
                        .content("{\"app\":\"driver\",\"fcmToken\":\"tok-driver\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/me/device").header("Authorization", token).contentType(JSON)
                        .content("{\"app\":\"passenger\",\"fcmToken\":\"tok-passenger\"}"))
                .andExpect(status().isNoContent());

        assertThat(firestore.get("drivers/dual").get("fcmToken")).isEqualTo("tok-driver");
        assertThat(firestore.get("passengers/dual").get("fcmToken")).isEqualTo("tok-passenger");
    }

    @Test
    void sin_perfil_en_esa_app_responde_404_y_no_crea_nada() throws Exception {
        mvc.perform(put("/api/me/device").header("Authorization", userToken("sinperfil"))
                        .contentType(JSON).content("{\"app\":\"driver\",\"fcmToken\":\"tok\"}"))
                .andExpect(status().isNotFound());

        assertThat(firestore.get("drivers/sinperfil")).isNull();
    }

    @Test
    void una_app_desconocida_es_un_400() throws Exception {
        mvc.perform(put("/api/me/device").header("Authorization", userToken("c1"))
                        .contentType(JSON).content("{\"app\":\"tablet\",\"fcmToken\":\"tok\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void un_idioma_que_el_servidor_no_sabe_armar_es_un_400() throws Exception {
        mvc.perform(put("/api/me/device").header("Authorization", userToken("c1"))
                        .contentType(JSON).content("{\"app\":\"driver\",\"language\":\"fr\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sin_token_ni_idioma_es_un_400() throws Exception {
        mvc.perform(put("/api/me/device").header("Authorization", userToken("c1"))
                        .contentType(JSON).content("{\"app\":\"driver\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sin_autenticar_responde_401() throws Exception {
        mvc.perform(put("/api/me/device").contentType(JSON).content("{\"app\":\"driver\",\"fcmToken\":\"t\"}"))
                .andExpect(status().isUnauthorized());
    }
}
