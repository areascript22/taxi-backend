package com.areascript.taxiapp.user.api;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.areascript.taxiapp.user.domain.DriverProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// El alta y la edición de perfil a nivel HTTP: filtro de seguridad, controller y
// validaciones reales; el almacenamiento (la base) es de mentira.
class ProfileEndpointTest extends CharacterizationTestBase {

    private static final String JSON = "application/json";
    // El bucket de dev (application-dev.properties).
    private static final String BUCKET = "taxigo-dev-96d02.firebasestorage.app";

    private static UUID idOf(String firebaseUid) {
        return UUID.nameUUIDFromBytes(firebaseUid.getBytes());
    }

    private static String photoOf(String folder, String uid) {
        return "https://firebasestorage.googleapis.com/v0/b/" + BUCKET + "/o/" + folder + "%2F" + uid
                + ".jpg?alt=media&token=abc";
    }

    private static String driverBody(String photoUrl, String plate, int year) {
        return "{\"firstName\":\"Ana\",\"lastName\":\"Pérez\",\"phoneNumber\":\"0991112222\","
                + (photoUrl == null ? "" : "\"photoUrl\":\"" + photoUrl + "\",")
                + "\"vehicle\":{\"plate\":\"" + plate + "\",\"brand\":\"Toyota\",\"model\":\"Corolla\","
                + "\"year\":" + year + ",\"color\":\"Amarillo\",\"registrationNumber\":\"REG-1\"}}";
    }

    // ---- Conductor ----

    @Test
    void sin_autenticar_responde_401() throws Exception {
        mvc.perform(put("/api/me/driver-profile").contentType(JSON).content(driverBody(null, "PBX-1234", 2020)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void crea_el_perfil_de_conductor_pendiente_de_aprobacion_con_su_vehiculo() throws Exception {
        mvc.perform(put("/api/me/driver-profile").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(driverBody(null, "PBX-1234", 2020)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driver.approvalStatus").value("pending"))
                .andExpect(jsonPath("$.driver.phoneNumber").value("0991112222"))
                .andExpect(jsonPath("$.driver.vehicle.plate").value("PBX-1234"))
                .andExpect(jsonPath("$.driver.online").value(false));

        assertThat(userProfiles.savedDriverProfiles()).hasSize(1);
    }

    // El cliente no decide su aprobación, su rol ni su bloqueo: aunque los mande,
    // el servidor los ignora (el request ni siquiera los modela).
    @Test
    void ignora_los_campos_que_solo_el_servidor_decide() throws Exception {
        String body = "{\"firstName\":\"Ana\",\"lastName\":\"Pérez\",\"phoneNumber\":\"0991112222\","
                + "\"approvalStatus\":\"approved\",\"role\":\"superuser\",\"isBlocked\":false,\"rating\":5,"
                + "\"vehicle\":{\"plate\":\"PBX-1234\",\"brand\":\"Toyota\",\"model\":\"Corolla\","
                + "\"year\":2020,\"color\":\"Amarillo\"}}";

        mvc.perform(put("/api/me/driver-profile").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driver.approvalStatus").value("pending"))
                .andExpect(jsonPath("$.role").value("user"));
    }

    @Test
    void editar_conserva_la_aprobacion_que_ya_tenia() throws Exception {
        userProfiles.putDriver(idOf("d1"), new DriverProfile("0990000000", null, 4.8, "approved", null, null, null, false, false));

        mvc.perform(put("/api/me/driver-profile").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(driverBody(null, "PBX-1234", 2020)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driver.approvalStatus").value("approved"))
                .andExpect(jsonPath("$.driver.rating").value(4.8));
    }

    // ---- Fundador: no espera la aprobación (nadie puede dársela), pero llena todo ----

    private String founderToken() throws Exception {
        return token(FOUNDER_UID, FOUNDER_EMAIL, "Fundador", null, java.util.Map.of());
    }

    @Test
    void el_fundador_crea_su_perfil_de_conductor_ya_aprobado_con_su_vehiculo() throws Exception {
        mvc.perform(put("/api/me/driver-profile").header("Authorization", founderToken())
                        .contentType(JSON).content(driverBody(null, "PBX-1234", 2020)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driver.approvalStatus").value("approved"))
                .andExpect(jsonPath("$.driver.vehicle.plate").value("PBX-1234"));

        assertThat(userProfiles.savedDriverProfiles()).hasSize(1);
    }

    // Se salta la espera, no el formulario: sin vehículo es un 400 como para
    // cualquier otro conductor y no se guarda nada.
    @Test
    void el_fundador_tambien_tiene_que_llenar_el_vehiculo() throws Exception {
        String sinVehiculo = "{\"firstName\":\"Ana\",\"lastName\":\"Pérez\",\"phoneNumber\":\"0991112222\"}";

        mvc.perform(put("/api/me/driver-profile").header("Authorization", founderToken())
                        .contentType(JSON).content(sinVehiculo))
                .andExpect(status().isBadRequest());

        assertThat(userProfiles.savedDriverProfiles()).isEmpty();
    }

    // Ya había registrado su perfil antes de esta regla (o en otra base): al
    // volver a guardarlo queda aprobado.
    @Test
    void el_fundador_con_el_perfil_pendiente_queda_aprobado_al_guardarlo() throws Exception {
        userProfiles.putDriver(idOf(FOUNDER_UID),
                new DriverProfile("0990000000", null, 5.0, "pending", null, null, null, false, false));

        mvc.perform(put("/api/me/driver-profile").header("Authorization", founderToken())
                        .contentType(JSON).content(driverBody(null, "PBX-1234", 2020)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driver.approvalStatus").value("approved"));
    }

    // Quién es el fundador lo decide el uid del token verificado, no el correo:
    // alguien con SU correo pero otro uid no se salta la aprobación.
    @Test
    void quien_solo_comparte_el_correo_del_fundador_sigue_pendiente() throws Exception {
        String impostor = token("otro-uid", FOUNDER_EMAIL, "Impostor", null, java.util.Map.of());

        mvc.perform(put("/api/me/driver-profile").header("Authorization", impostor)
                        .contentType(JSON).content(driverBody(null, "PBX-1234", 2020)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driver.approvalStatus").value("pending"));
    }

    @Test
    void acepta_la_foto_que_es_suya() throws Exception {
        mvc.perform(put("/api/me/driver-profile").header("Authorization", userToken("d1"))
                        .contentType(JSON)
                        .content(driverBody(photoOf("driver_profile_photos", "d1"), "PBX-1234", 2020)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driver.photoUrl").value(photoOf("driver_profile_photos", "d1")));
    }

    @Test
    void rechaza_la_foto_de_otra_persona() throws Exception {
        mvc.perform(put("/api/me/driver-profile").header("Authorization", userToken("d1"))
                        .contentType(JSON)
                        .content(driverBody(photoOf("driver_profile_photos", "otro"), "PBX-1234", 2020)))
                .andExpect(status().isBadRequest());

        assertThat(userProfiles.savedDriverProfiles()).isEmpty();
    }

    @Test
    void rechaza_una_foto_de_un_sitio_externo() throws Exception {
        mvc.perform(put("/api/me/driver-profile").header("Authorization", userToken("d1"))
                        .contentType(JSON)
                        .content(driverBody("https://evil.example.com/foto.jpg", "PBX-1234", 2020)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cada_dato_obligatorio_faltante_es_un_400_y_no_guarda_nada() throws Exception {
        String sinNombre = "{\"lastName\":\"P\",\"phoneNumber\":\"0991112222\",\"vehicle\":"
                + "{\"plate\":\"A\",\"brand\":\"B\",\"model\":\"M\",\"year\":2020,\"color\":\"C\"}}";
        String sinVehiculo = "{\"firstName\":\"Ana\",\"lastName\":\"P\",\"phoneNumber\":\"0991112222\"}";
        String sinPlaca = driverBody(null, "  ", 2020);
        String sinAnio = "{\"firstName\":\"Ana\",\"lastName\":\"P\",\"phoneNumber\":\"0991112222\",\"vehicle\":"
                + "{\"plate\":\"A\",\"brand\":\"B\",\"model\":\"M\",\"color\":\"C\"}}";

        for (String body : new String[]{sinNombre, sinVehiculo, sinPlaca, sinAnio}) {
            mvc.perform(put("/api/me/driver-profile").header("Authorization", userToken("d1"))
                            .contentType(JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(userProfiles.savedDriverProfiles()).isEmpty();
    }

    @Test
    void un_telefono_invalido_es_un_400() throws Exception {
        String body = driverBody(null, "PBX-1234", 2020).replace("0991112222", "llamame");

        mvc.perform(put("/api/me/driver-profile").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void un_anio_fuera_de_rango_es_un_400() throws Exception {
        mvc.perform(put("/api/me/driver-profile").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(driverBody(null, "PBX-1234", 1900)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/driver-profile").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(driverBody(null, "PBX-1234", 2999)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void un_texto_mas_largo_que_el_limite_de_la_base_es_un_400_y_no_un_500() throws Exception {
        String body = driverBody(null, "P".repeat(40), 2020);

        mvc.perform(put("/api/me/driver-profile").header("Authorization", userToken("d1"))
                        .contentType(JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    // ---- Pasajero ----

    @Test
    void crea_el_perfil_de_pasajero() throws Exception {
        mvc.perform(put("/api/me/passenger-profile").header("Authorization", userToken("p1"))
                        .contentType(JSON).content("{\"firstName\":\"Luis\",\"lastName\":\"Mora\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passenger.status").value("active"))
                .andExpect(jsonPath("$.driver").doesNotExist());
    }

    @Test
    void rechaza_la_foto_de_pasajero_de_otra_persona() throws Exception {
        String body = "{\"firstName\":\"Luis\",\"lastName\":\"Mora\",\"photoUrl\":\""
                + photoOf("passenger_profile_photos", "otro") + "\"}";

        mvc.perform(put("/api/me/passenger-profile").header("Authorization", userToken("p1"))
                        .contentType(JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void un_pasajero_no_puede_usar_la_carpeta_de_fotos_de_conductor() throws Exception {
        String body = "{\"firstName\":\"Luis\",\"lastName\":\"Mora\",\"photoUrl\":\""
                + photoOf("driver_profile_photos", "p1") + "\"}";

        mvc.perform(put("/api/me/passenger-profile").header("Authorization", userToken("p1"))
                        .contentType(JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void una_misma_cuenta_puede_tener_los_dos_perfiles() throws Exception {
        String token = userToken("dual");

        mvc.perform(put("/api/me/driver-profile").header("Authorization", token)
                        .contentType(JSON).content(driverBody(null, "PBX-1234", 2020)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/me/passenger-profile").header("Authorization", token)
                        .contentType(JSON).content("{\"firstName\":\"Ana\",\"lastName\":\"Pérez\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driver.approvalStatus").value("pending"))
                .andExpect(jsonPath("$.passenger.status").value("active"));
    }
}
