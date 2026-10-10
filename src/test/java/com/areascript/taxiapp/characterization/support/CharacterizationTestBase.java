package com.areascript.taxiapp.characterization.support;

import com.areascript.taxiapp.bootstrap.ApprovalStatusBackfill;
import com.areascript.taxiapp.bootstrap.SuperUserBootstrap;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Base de los tests de caracterización (fase 0 del refactor de arquitectura).
//
// Objetivo: congelar el comportamiento HTTP ACTUAL de los endpoints antes de
// mover código. Por eso solo se simula el borde con el mundo exterior
// (Firebase y el scheduler) y se levanta el resto del servidor real: filtro de
// seguridad, controllers, services y NotificationService con su catálogo de
// mensajes. Los tests hablan HTTP y verifican datos en las bases simuladas,
// así que no dependen de cómo estén organizadas las clases por dentro y
// deben seguir pasando, sin cambios, durante todo el refactor.
//
// Si un test documenta un comportamiento que es un BUG conocido, lo dice en su
// nombre/comentario ("bug conocido"): cuando la fase que lo corrige cambie ese
// comportamiento a propósito, se actualiza ese test, y solo ese.
//
// Ningún test toca Firebase real: todas las piezas de Firebase son mocks y
// los CommandLineRunner (bootstrap de superuser, backfill) están anulados.
//
// El fundador se fija acá con valores de prueba: así los tests no dependen de
// lo que haya en secrets/taxiapp-secrets.properties de cada máquina.
@SpringBootTest(properties = {
        "taxiapp.admin.founder-uid=" + CharacterizationTestBase.FOUNDER_UID,
        "taxiapp.admin.founder-email=" + CharacterizationTestBase.FOUNDER_EMAIL
})
public abstract class CharacterizationTestBase {

    protected static final String RIDES = "taxi_requests";
    protected static final String FOUNDER_UID = "founder-uid";
    protected static final String FOUNDER_EMAIL = "founder@test.com";

    @MockitoBean
    private FirebaseApp firebaseApp;

    @MockitoBean
    protected FirebaseAuth firebaseAuth;

    @MockitoBean
    private FirebaseDatabase firebaseDatabase;

    @MockitoBean
    private Firestore firestoreMock;

    @MockitoBean
    protected FirebaseMessaging firebaseMessaging;

    @MockitoBean
    protected TaskScheduler taskScheduler;

    // Corren al arrancar y hablarían con Firebase real.
    @MockitoBean
    private SuperUserBootstrap superUserBootstrap;

    @MockitoBean
    private ApprovalStatusBackfill approvalStatusBackfill;

    @Autowired
    private WebApplicationContext context;

    protected MockMvc mvc;
    protected FakeRealtimeDatabase rtdb;
    protected FakeFirestore firestore;

    @BeforeEach
    void setUpCharacterization() throws Exception {
        rtdb = new FakeRealtimeDatabase();
        rtdb.install(firebaseDatabase);

        firestore = new FakeFirestore();
        firestore.install(firestoreMock);

        // Cualquier token que el test no haya registrado es inválido.
        when(firebaseAuth.verifyIdToken(anyString())).thenThrow(authError(AuthErrorCode.INVALID_ID_TOKEN));

        // Igual que en el contenedor real: todos los Filter del contexto
        // (incluido FirebaseAuthenticationFilter) envuelven al dispatcher.
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(Filter[]::new);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
    }

    // ---- Identidades ----

    protected String userToken(String uid) throws Exception {
        return token(uid, uid + "@test.com", "Nombre " + uid, "https://photos/" + uid + ".jpg", Map.of());
    }

    protected String adminToken(String uid) throws Exception {
        return token(uid, uid + "@test.com", "Admin " + uid, null, Map.of("admin", true));
    }

    protected String superUserToken(String uid) throws Exception {
        return token(uid, uid + "@test.com", "Super " + uid, null, Map.of("admin", true, "superuser", true));
    }

    protected String token(String uid, String email, String name, String picture, Map<String, Object> claims)
            throws Exception {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn(uid);
        when(token.getEmail()).thenReturn(email);
        when(token.getName()).thenReturn(name);
        when(token.getPicture()).thenReturn(picture);
        when(token.getClaims()).thenReturn(new HashMap<>(claims));

        // doReturn y no when(): when() invocaría verifyIdToken, que por
        // defecto lanza (ver setUpCharacterization).
        String raw = "token-" + uid;
        doReturn(token).when(firebaseAuth).verifyIdToken(raw);
        return "Bearer " + raw;
    }

    // Usuario tal como lo ve Firebase Auth (custom claims), para las acciones
    // de moderación y borrado que consultan getUser antes de actuar.
    protected void authUser(String uid, Map<String, Object> customClaims) throws Exception {
        UserRecord user = mock(UserRecord.class);
        when(user.getUid()).thenReturn(uid);
        when(user.getCustomClaims()).thenReturn(customClaims);
        when(firebaseAuth.getUser(uid)).thenReturn(user);
    }

    protected static FirebaseAuthException authError(AuthErrorCode code) {
        ErrorCode errorCode = code == AuthErrorCode.USER_NOT_FOUND ? ErrorCode.NOT_FOUND : ErrorCode.INVALID_ARGUMENT;
        return new FirebaseAuthException(errorCode, "simulado: " + code, null, null, code);
    }

    // ---- Efectos laterales ----

    protected List<SentPush> sentPushes() throws Exception {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(firebaseMessaging, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream().map(SentPush::from).toList();
    }

    protected record ScheduledTask(Runnable task, Instant at) {
    }

    protected List<ScheduledTask> scheduledTasks() {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> instants = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, atLeast(0)).schedule(tasks.capture(), instants.capture());
        List<Runnable> capturedTasks = tasks.getAllValues();
        List<Instant> capturedInstants = instants.getAllValues();
        return java.util.stream.IntStream.range(0, capturedTasks.size())
                .mapToObj(i -> new ScheduledTask(capturedTasks.get(i), capturedInstants.get(i)))
                .toList();
    }

    // ---- Datos de ejemplo ----

    // Documento de Firestore de un conductor, con lo mínimo que lee el servidor.
    protected void driverProfile(String uid, String vehicleId, String fcmToken, String language) {
        Map<String, Object> data = new HashMap<>();
        data.put("firstName", "Conductor");
        data.put("lastName", uid);
        data.put("email", uid + "@test.com");
        data.put("phoneNumber", "0990000000");
        data.put("fcmToken", fcmToken);
        data.put("language", language);
        if (vehicleId != null) {
            data.put("vehicleId", vehicleId);
        }
        firestore.put("drivers/" + uid, data);
    }

    protected void passengerProfile(String uid, String fcmToken, String language) {
        Map<String, Object> data = new HashMap<>();
        data.put("fullName", "Pasajero " + uid);
        data.put("fcmToken", fcmToken);
        data.put("language", language);
        firestore.put("passengers/" + uid, data);
    }

    protected void vehicle(String vehicleId, String driverUid) {
        firestore.put("vehicles/" + vehicleId, Map.of(
                "driverId", driverUid,
                "plate", "PBX-1234",
                "brand", "Toyota",
                "model", "Corolla",
                "color", "Amarillo",
                "year", 2020L
        ));
    }

    // Nodo de Realtime DB de una carrera, en el formato que escribe el servidor.
    protected Map<String, Object> rideNode(String passengerUid, String status, String driverUid) {
        Map<String, Object> ride = new HashMap<>();
        ride.put("rideId", passengerUid + "_1700000000000");
        ride.put("userId", passengerUid);
        ride.put("status", status);
        ride.put("passenger", Map.of("name", "Pasajero " + passengerUid, "profileImage", "https://p.jpg"));
        ride.put("pickupLocation", Map.of(
                "latitude", -1.6635,
                "longitude", -78.6546,
                "address", "Av. Daniel León Borja",
                "sector", "La Saboya"
        ));
        ride.put("createdAt", 1L);
        ride.put("updatedAt", 1L);
        if (driverUid != null) {
            ride.put("driver", Map.of(
                    "data", Map.of("id", driverUid, "displayName", "Conductor " + driverUid),
                    "location", Map.of("latitude", -1.67, "longitude", -78.65)
            ));
        }
        return ride;
    }

    protected void givenRide(String passengerUid, String status, String driverUid) {
        rtdb.put(RIDES + "/" + passengerUid, rideNode(passengerUid, status, driverUid));
    }

    protected Object rideField(String passengerUid, String fieldPath) {
        return rtdb.get(RIDES + "/" + passengerUid + "/" + fieldPath);
    }
}
