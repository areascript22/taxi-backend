package com.areascript.taxiapp.driveradmin.application;

import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.DriverPage;
import com.areascript.taxiapp.driveradmin.domain.DriverRepository;
import com.areascript.taxiapp.driveradmin.domain.DriverRole;
import com.areascript.taxiapp.driveradmin.domain.Driver;
import com.areascript.taxiapp.user.domain.UserAccount;
import com.areascript.taxiapp.user.domain.UserAccounts;
import com.areascript.taxiapp.user.domain.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// El arranque del fundador contra puertos en memoria: sin Firebase ni Spring.
class FounderBootstrapServiceTest {

    private static final TaxiappProperties.Rides RIDES =
            new TaxiappProperties.Rides(Duration.ofSeconds(35), Duration.ofSeconds(10));
    private static final TaxiappProperties.Chat CHAT =
            new TaxiappProperties.Chat(1000, 80, Duration.ofDays(7));

    private final List<String> calls = new ArrayList<>();

    private RuntimeException ensureRoleFailure;

    // La fila de conductor del fundador, si ya registró su perfil. Vacía = nunca
    // lo hizo (en la base real, quien no tiene perfil no aparece como conductor).
    private Optional<Driver> founderDriver = Optional.empty();
    private RuntimeException findByIdFailure;
    private RuntimeException applyApprovalFailure;

    private static Driver driverWith(String uid, ApprovalStatus status) {
        return new Driver(uid, "Fundador", "Dueño", "f@test.com", "0991112222", null, null, 5.0,
                DriverRole.SUPERUSER, status, false, null, null, null, null, null, null, null);
    }

    private final DriverRepository drivers = new DriverRepository() {
        @Override
        public Optional<Driver> findById(String uid) {
            if (findByIdFailure != null) {
                throw findByIdFailure;
            }
            return founderDriver;
        }

        @Override
        public DriverPage findAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DriverPage findPage(int pageSize, String cursor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateRole(String uid, DriverRole role) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void ensureRole(String uid, DriverRole role) {
            if (ensureRoleFailure != null) {
                throw ensureRoleFailure;
            }
            calls.add("ensureRole " + uid + " " + role.wireValue());
        }

        @Override
        public void applyApproval(String uid, ApprovalStatus status, String reason, String actorUid) {
            if (applyApprovalFailure != null) {
                throw applyApprovalFailure;
            }
            calls.add("applyApproval " + uid + " " + status.wireValue() + " por " + actorUid);
        }

        @Override
        public void applyBlock(String uid, boolean blocked, String reason, String actorUid) {
            throw new UnsupportedOperationException();
        }
    };

    // La base de usuarios, que es la que decide los permisos en cada request.
    private final List<String> userRoleCalls = new ArrayList<>();
    private RuntimeException userRoleFailure;

    private final UserAccounts userAccounts = new UserAccounts() {
        @Override
        public UserAccount findOrProvision(String firebaseUid, String email, String displayName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UserAccount> find(String firebaseUid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UserAccount> findById(java.util.UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setRole(String firebaseUid, UserRole role) {
            if (userRoleFailure != null) {
                throw userRoleFailure;
            }
            userRoleCalls.add(firebaseUid + " " + role.code());
        }

        @Override
        public void remove(String firebaseUid) {
            throw new UnsupportedOperationException();
        }
    };

    private FounderBootstrapService service(String founderUid) {
        TaxiappProperties properties = new TaxiappProperties(
                RIDES, CHAT, new TaxiappProperties.Admin(founderUid, "founder@test.com"));
        return new FounderBootstrapService(drivers, userAccounts, properties);
    }

    @Test
    void sin_fundador_configurado_no_hace_nada() {
        service("").ensureFounderIsSuperUser();
        service(null).ensureFounderIsSuperUser();

        assertThat(calls).isEmpty();
    }

    // Idempotente: cada arranque re-sincroniza el rol por si una corrida
    // anterior falló a mitad de camino.
    @Test
    void sincroniza_el_rol_del_fundador() {
        service("f1").ensureFounderIsSuperUser();

        assertThat(calls).containsExactly("ensureRole f1 superuser");
    }

    // El rol que manda en cada request es el de la base de usuarios.
    @Test
    void el_fundador_queda_como_superuser_en_la_base_de_usuarios() {
        service("f1").ensureFounderIsSuperUser();

        assertThat(userRoleCalls).containsExactly("f1 superuser");
    }

    // Nunca impide el arranque.
    @Test
    void un_fallo_al_sincronizar_el_rol_no_se_propaga_y_se_intenta_el_otro_paso() {
        ensureRoleFailure = new ExternalServiceException("firestore caído", new RuntimeException());

        assertThatCode(() -> service("f1").ensureFounderIsSuperUser()).doesNotThrowAnyException();
        assertThat(userRoleCalls).containsExactly("f1 superuser");
    }

    @Test
    void un_fallo_al_guardar_el_rol_en_la_base_no_se_propaga() {
        userRoleFailure = new ExternalServiceException("postgres caído", new RuntimeException());

        assertThatCode(() -> service("f1").ensureFounderIsSuperUser()).doesNotThrowAnyException();
    }

    // ---- El fundador no espera una aprobación que nadie puede darle ----

    @Test
    void aprueba_el_perfil_de_conductor_del_fundador_si_estaba_pendiente() {
        founderDriver = Optional.of(driverWith("f1", ApprovalStatus.PENDING));

        service("f1").ensureFounderIsSuperUser();

        // Él mismo es el actor: no hay nadie por encima que lo apruebe.
        assertThat(calls).containsExactly("ensureRole f1 superuser", "applyApproval f1 approved por f1");
    }

    @Test
    void tambien_lo_aprueba_si_estaba_rechazado() {
        founderDriver = Optional.of(driverWith("f1", ApprovalStatus.REJECTED));

        service("f1").ensureFounderIsSuperUser();

        assertThat(calls).contains("applyApproval f1 approved por f1");
    }

    // Idempotente: en cada arranque no reescribe una aprobación que ya está.
    @Test
    void no_vuelve_a_aprobar_si_ya_estaba_aprobado() {
        founderDriver = Optional.of(driverWith("f1", ApprovalStatus.APPROVED));

        service("f1").ensureFounderIsSuperUser();

        assertThat(calls).containsExactly("ensureRole f1 superuser");
    }

    // En una base nueva el fundador todavía no registró su perfil: nacerá
    // aprobado al guardarlo (DriverApprovalRules), aquí no hay nada que hacer.
    @Test
    void sin_perfil_de_conductor_no_hay_nada_que_aprobar() {
        service("f1").ensureFounderIsSuperUser();

        assertThat(calls).containsExactly("ensureRole f1 superuser");
    }

    // Nunca impide el arranque, y el rol ya quedó guardado.
    @Test
    void un_fallo_al_aprobar_no_se_propaga() {
        founderDriver = Optional.of(driverWith("f1", ApprovalStatus.PENDING));
        applyApprovalFailure = new ExternalServiceException("postgres caído", new RuntimeException());

        assertThatCode(() -> service("f1").ensureFounderIsSuperUser()).doesNotThrowAnyException();
        assertThat(userRoleCalls).containsExactly("f1 superuser");
    }

    @Test
    void un_fallo_al_leer_al_conductor_no_se_propaga() {
        findByIdFailure = new ExternalServiceException("postgres caído", new RuntimeException());

        assertThatCode(() -> service("f1").ensureFounderIsSuperUser()).doesNotThrowAnyException();
        assertThat(userRoleCalls).containsExactly("f1 superuser");
    }

    @Test
    void sin_fundador_configurado_tampoco_aprueba_a_nadie() {
        founderDriver = Optional.of(driverWith("f1", ApprovalStatus.PENDING));

        service("").ensureFounderIsSuperUser();

        assertThat(calls).isEmpty();
    }
}