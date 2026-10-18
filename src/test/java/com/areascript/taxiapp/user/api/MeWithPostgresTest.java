package com.areascript.taxiapp.user.api;

import com.areascript.taxiapp.database.support.SharedPostgis;
import com.areascript.taxiapp.driveradmin.api.SuperUserBootstrap;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessaging;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// El camino REAL de punta a punta: filtro de seguridad -> adaptador de Postgres
// -> controller, contra una base real con las migraciones aplicadas y la
// configuración de producción (persistencia activa). Solo Firebase es de mentira.
//
// Es el único test que levanta el contexto con los adaptadores de Postgres
// cableados; los de caracterización los reemplazan por una base en memoria.
//
// Se omite si Docker no está disponible.
@SpringBootTest(properties = {
        "taxiapp.admin.founder-uid=",
        "taxiapp.admin.founder-email="
})
@EnabledIf("dockerAvailable")
class MeWithPostgresTest {

    static boolean dockerAvailable() {
        return SharedPostgis.dockerAvailable();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        SharedPostgis.registerDatasource(registry);
    }

    @MockitoBean
    private FirebaseApp firebaseApp;
    @MockitoBean
    private FirebaseAuth firebaseAuth;
    @MockitoBean
    private FirebaseDatabase firebaseDatabase;
    @MockitoBean
    private Firestore firestore;
    @MockitoBean
    private FirebaseMessaging firebaseMessaging;
    @MockitoBean
    private TaskScheduler taskScheduler;
    @MockitoBean
    private SuperUserBootstrap superUserBootstrap;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM users");   // en cascada: perfiles y vehículos
        when(firebaseAuth.verifyIdToken(anyString())).thenThrow(new IllegalStateException("token no registrado"));

        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(Filter[]::new);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
    }

    // Este test NO es transaccional (la petición HTTP y el test son hilos/commits
    // distintos), y la base se comparte con otros tests que cuentan filas: se
    // deja limpia al terminar. Los perfiles y vehículos se borran en cascada.
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users");
    }

    private String token(String uid, String email, String name, Map<String, Object> claims) throws Exception {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn(uid);
        when(token.getEmail()).thenReturn(email);
        when(token.getName()).thenReturn(name);
        when(token.getClaims()).thenReturn(claims);
        String raw = "token-" + uid;
        doReturn(token).when(firebaseAuth).verifyIdToken(raw);
        return "Bearer " + raw;
    }

    @Test
    void la_primera_peticion_crea_a_la_persona_en_postgres() throws Exception {
        String token = token("uid-nuevo", "nuevo@test.com", "Nuevo Usuario", Map.of());

        mvc.perform(get("/api/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("uid-nuevo"))
                .andExpect(jsonPath("$.email").value("nuevo@test.com"))
                .andExpect(jsonPath("$.displayName").value("Nuevo Usuario"))
                .andExpect(jsonPath("$.role").value("user"));

        assertThat(jdbc.queryForObject(
                "SELECT role_code FROM users WHERE firebase_uid = 'uid-nuevo'", String.class)).isEqualTo("user");
    }

    @Test
    void peticiones_repetidas_no_duplican_a_la_persona() throws Exception {
        String token = token("uid-1", "a@test.com", "A", Map.of());

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isOk());
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
    }

    // El rol que manda es el de la fila, y un cambio en la base vale en la
    // siguiente petición, aunque el token siga trayendo los mismos claims.
    @Test
    void un_cambio_de_rol_en_la_base_vale_en_la_siguiente_peticion() throws Exception {
        String token = token("uid-2", "b@test.com", "B", Map.of());

        mvc.perform(get("/api/me").header("Authorization", token))
                .andExpect(jsonPath("$.role").value("user"));
        mvc.perform(get("/api/drivers").header("Authorization", token))
                .andExpect(status().isForbidden());

        jdbc.update("UPDATE users SET role_code = 'admin' WHERE firebase_uid = 'uid-2'");

        mvc.perform(get("/api/me").header("Authorization", token))
                .andExpect(jsonPath("$.role").value("admin"));
    }

    @Test
    void un_claim_de_admin_sin_rol_en_la_base_no_abre_el_panel() throws Exception {
        String token = token("uid-3", "c@test.com", "C", Map.of("admin", true, "superuser", true));

        mvc.perform(get("/api/drivers").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    // El camino completo de un conductor nuevo, sin Firestore de por medio:
    // se registra, queda pendiente, un administrador lo aprueba y recién ahí
    // puede ponerse en línea y reportar su ubicación.
    @Test
    void un_conductor_nuevo_se_registra_es_aprobado_y_puede_trabajar() throws Exception {
        String token = token("nuevo-conductor", "nuevo@test.com", "Nuevo Conductor", Map.of());
        String body = "{\"firstName\":\"Ana\",\"lastName\":\"Pérez\",\"phoneNumber\":\"0991112222\","
                + "\"vehicle\":{\"plate\":\"PBX-1234\",\"brand\":\"Toyota\",\"model\":\"Corolla\","
                + "\"year\":2020,\"color\":\"Amarillo\",\"registrationNumber\":\"REG-1\"}}";

        mvc.perform(put("/api/me/driver-profile").header("Authorization", token)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driver.approvalStatus").value("pending"))
                .andExpect(jsonPath("$.driver.vehicle.plate").value("PBX-1234"))
                .andExpect(jsonPath("$.displayName").value("Ana Pérez"));

        // Pendiente: no puede ponerse en línea.
        mvc.perform(put("/api/me/driver/availability").header("Authorization", token)
                        .contentType("application/json").content("{\"online\":true}"))
                .andExpect(status().isForbidden());

        // Un administrador lo aprueba.
        jdbc.update("UPDATE drivers SET approval_status = 'approved' WHERE user_id = "
                + "(SELECT id FROM users WHERE firebase_uid = 'nuevo-conductor')");

        mvc.perform(put("/api/me/driver/availability").header("Authorization", token)
                        .contentType("application/json").content("{\"online\":true}"))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/me/driver/location").header("Authorization", token)
                        .contentType("application/json").content("{\"latitude\":-1.6635,\"longitude\":-78.6546}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM driver_locations", Integer.class)).isEqualTo(1);
    }

    @Test
    void devuelve_el_perfil_de_conductor_y_su_vehiculo() throws Exception {
        String token = token("uid-4", "d@test.com", "D", Map.of());
        mvc.perform(get("/api/me").header("Authorization", token)).andExpect(status().isOk());
        jdbc.update("INSERT INTO drivers (user_id, phone_number, approval_status) "
                + "SELECT id, '0991112222', 'approved' FROM users WHERE firebase_uid = 'uid-4'");
        jdbc.update("INSERT INTO vehicles (vehicle_id, driver_id, plate, year) "
                + "SELECT 'veh-4', id, 'PBX-1234', 2020 FROM users WHERE firebase_uid = 'uid-4'");
        jdbc.update("UPDATE drivers SET vehicle_id = 'veh-4' WHERE vehicle_id IS NULL "
                + "AND user_id = (SELECT id FROM users WHERE firebase_uid = 'uid-4')");

        mvc.perform(get("/api/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driver.approvalStatus").value("approved"))
                .andExpect(jsonPath("$.driver.vehicle.plate").value("PBX-1234"))
                .andExpect(jsonPath("$.passenger").doesNotExist());
    }
}
