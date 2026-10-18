package com.areascript.taxiapp.common.error;

import com.areascript.taxiapp.characterization.support.CharacterizationTestBase;
import com.google.firebase.database.DatabaseError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Formato de los errores (GlobalExceptionHandler). Los CÓDIGOS ya los cubren
// los tests de caracterización; acá se verifica el cuerpo ProblemDetail.
class ErrorResponseTest extends CharacterizationTestBase {

    private static final String DRIVER_LOCATION = "{\"latitude\": -1.67, \"longitude\": -78.65}";

    // Para que aceptar llegue hasta el estado de la carrera, el conductor tiene
    // que estar habilitado (aprobado y no bloqueado).
    @BeforeEach
    void approvedDriver() {
        driverProfile("d1", null, "fcm-d1", "es");
    }

    @Test
    void un_error_de_negocio_responde_problem_detail_con_el_motivo() throws Exception {
        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", userToken("d1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail", containsString("passengerId: p1")));
    }

    @Test
    void un_conflicto_explica_el_motivo() throws Exception {
        givenRide("p1", "driverAssigned", "d2");

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", userToken("d1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail", containsString("ya fue tomada")));
    }

    // Un fallo de Firebase responde 500 sin filtrar el detalle interno.
    @Test
    void un_fallo_de_firebase_responde_500_con_un_mensaje_generico() throws Exception {
        givenRide("p1", "pending", null);
        rtdb.failTransactionsOn(RIDES + "/p1", DatabaseError.fromCode(DatabaseError.UNAVAILABLE));

        mvc.perform(post("/api/rides/p1/accept")
                        .header("Authorization", userToken("d1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DRIVER_LOCATION))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.INTERNAL_ERROR_DETAIL));
    }

    @Test
    void un_json_mal_formado_responde_400_con_problem_detail() throws Exception {
        mvc.perform(post("/api/rides/request")
                        .header("Authorization", userToken("p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{esto no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void un_parametro_con_tipo_invalido_responde_400() throws Exception {
        mvc.perform(get("/api/drivers").param("pageSize", "muchos").header("Authorization", adminToken("a1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void cada_tipo_de_error_mapea_a_su_codigo_http() {
        assertThat(GlobalExceptionHandler.toHttpStatus(ErrorType.INVALID_INPUT).value()).isEqualTo(400);
        assertThat(GlobalExceptionHandler.toHttpStatus(ErrorType.FORBIDDEN).value()).isEqualTo(403);
        assertThat(GlobalExceptionHandler.toHttpStatus(ErrorType.NOT_FOUND).value()).isEqualTo(404);
        assertThat(GlobalExceptionHandler.toHttpStatus(ErrorType.CONFLICT).value()).isEqualTo(409);
        assertThat(GlobalExceptionHandler.toHttpStatus(ErrorType.EXTERNAL_SERVICE).value()).isEqualTo(500);
    }
}
