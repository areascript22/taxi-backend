package com.areascript.taxiapp.characterization;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.google.firebase.auth.AuthErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// DELETE /api/account: el usuario borra su propia cuenta desde Ajustes.
class AccountDeletionCharacterizationTest extends CharacterizationTestBase {

    private void driverWithData() {
        driverProfile("d1", "v1", "fcm-d1", "es");
        vehicle("v1", "d1");
        firestore.put("chats/r1", Map.of("driverId", "d1", "passengerId", "p9"));
        firestore.put("chats/r1/messages/m1", Map.of("text", "hola"));
        firestore.put("chats/r1/messages/m2", Map.of("text", "chau"));
        firestore.put("chats/r2", Map.of("driverId", "d2", "passengerId", "p9"));
        firestore.put("chats/r2/messages/m1", Map.of("text", "de otro"));
    }

    @Test
    void borra_todos_los_datos_del_conductor_y_su_usuario_de_auth() throws Exception {
        driverWithData();

        mvc.perform(delete("/api/account").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());

        assertThat(firestore.exists("drivers/d1")).isFalse();
        assertThat(firestore.exists("vehicles/v1")).isFalse();
        assertThat(firestore.exists("chats/r1")).isFalse();
        assertThat(firestore.documentIds("chats/r1/messages")).isEmpty();
        verify(firebaseAuth).deleteUser("d1");
    }

    @Test
    void no_toca_los_chats_en_los_que_no_participa() throws Exception {
        driverWithData();

        mvc.perform(delete("/api/account").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());

        assertThat(firestore.exists("chats/r2")).isTrue();
        assertThat(firestore.documentIds("chats/r2/messages")).containsExactly("m1");
    }

    @Test
    void borra_el_documento_del_pasajero_y_sus_chats() throws Exception {
        passengerProfile("p9", "fcm-p9", "es");
        firestore.put("chats/r1", Map.of("driverId", "d1", "passengerId", "p9"));

        mvc.perform(delete("/api/account").header("Authorization", userToken("p9")))
                .andExpect(status().isNoContent());

        assertThat(firestore.exists("passengers/p9")).isFalse();
        assertThat(firestore.exists("chats/r1")).isFalse();
        verify(firebaseAuth).deleteUser("p9");
    }

    @Test
    void con_una_solicitud_pending_del_pasajero_responde_409_sin_borrar_nada() throws Exception {
        passengerProfile("p1", "fcm-p1", "es");
        givenRide("p1", "pending", null);

        mvc.perform(delete("/api/account").header("Authorization", userToken("p1")))
                .andExpect(status().isConflict());

        assertThat(firestore.exists("passengers/p1")).isTrue();
        verify(firebaseAuth, never()).deleteUser(anyString());
    }

    @Test
    void con_un_viaje_asignado_al_conductor_responde_409_sin_borrar_nada() throws Exception {
        driverWithData();
        givenRide("p1", "tripStarted", "d1");

        mvc.perform(delete("/api/account").header("Authorization", userToken("d1")))
                .andExpect(status().isConflict());

        assertThat(firestore.exists("drivers/d1")).isTrue();
        verify(firebaseAuth, never()).deleteUser(anyString());
    }

    // Idempotente: si un reintento del cliente llega cuando el usuario de
    // Auth ya fue borrado, se responde éxito igual.
    @Test
    void si_el_usuario_de_auth_ya_no_existe_responde_204() throws Exception {
        driverWithData();
        doThrow(authError(AuthErrorCode.USER_NOT_FOUND)).when(firebaseAuth).deleteUser("d1");

        mvc.perform(delete("/api/account").header("Authorization", userToken("d1")))
                .andExpect(status().isNoContent());
    }

    // Auth se borra al final: si falla, los datos ya están borrados pero el
    // usuario conserva un token válido y puede reintentar.
    @Test
    void si_falla_el_borrado_en_auth_responde_500_con_los_datos_ya_borrados() throws Exception {
        driverWithData();
        doThrow(authError(AuthErrorCode.INVALID_ID_TOKEN)).when(firebaseAuth).deleteUser("d1");

        mvc.perform(delete("/api/account").header("Authorization", userToken("d1")))
                .andExpect(status().isInternalServerError());

        assertThat(firestore.exists("drivers/d1")).isFalse();
    }

    @Test
    void sin_token_responde_401() throws Exception {
        driverWithData();

        mvc.perform(delete("/api/account"))
                .andExpect(status().isUnauthorized());

        assertThat(firestore.exists("drivers/d1")).isTrue();
    }
}
