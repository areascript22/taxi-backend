package com.areascript.taxiapp.driveradmin.application;

import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.DriverPage;
import com.areascript.taxiapp.driveradmin.domain.DriverRepository;
import com.areascript.taxiapp.driveradmin.domain.DriverRole;
import com.areascript.taxiapp.driveradmin.domain.Driver;
import com.areascript.taxiapp.identity.domain.AccountRoles;
import com.areascript.taxiapp.identity.domain.IdentityProvider;
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

    private Optional<AccountRoles> accountRoles = Optional.empty();
    private RuntimeException identityFailure;
    private RuntimeException ensureRoleFailure;

    private final IdentityProvider identity = new IdentityProvider() {
        @Override
        public Optional<AccountRoles> findRoles(String uid) {
            if (identityFailure != null) {
                throw identityFailure;
            }
            return accountRoles;
        }

        @Override
        public void setRoles(String uid, boolean admin, boolean superUser) {
            calls.add("setRoles " + uid + " admin=" + admin + " superUser=" + superUser);
        }

        @Override
        public void delete(String uid) {
            throw new UnsupportedOperationException();
        }
    };

    private final DriverRepository drivers = new DriverRepository() {
        @Override
        public Optional<Driver> findById(String uid) {
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException();
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
        return new FounderBootstrapService(identity, drivers, userAccounts, properties);
    }

    @Test
    void sin_fundador_configurado_no_hace_nada() {
        service("").ensureFounderIsSuperUser();
        service(null).ensureFounderIsSuperUser();

        assertThat(calls).isEmpty();
    }

    @Test
    void si_la_cuenta_no_existe_en_este_proyecto_se_omite() {
        accountRoles = Optional.empty();

        service("f1").ensureFounderIsSuperUser();

        assertThat(calls).isEmpty();
    }

    @Test
    void promueve_a_superuser_y_sincroniza_el_rol() {
        accountRoles = Optional.of(AccountRoles.NONE);

        service("f1").ensureFounderIsSuperUser();

        assertThat(calls).containsExactly(
                "setRoles f1 admin=true superUser=true",
                "ensureRole f1 superuser");
    }

    // Idempotente: no reasigna un claim que ya está, pero sí re-sincroniza el
    // rol por si una corrida anterior falló a mitad de camino.
    @Test
    void si_ya_es_superuser_solo_sincroniza_el_rol() {
        accountRoles = Optional.of(new AccountRoles(true, true));

        service("f1").ensureFounderIsSuperUser();

        assertThat(calls).containsExactly("ensureRole f1 superuser");
    }

    // Nunca impide el arranque.
    @Test
    void un_fallo_al_consultar_no_se_propaga() {
        identityFailure = new ExternalServiceException("firebase caído", new RuntimeException());

        assertThatCode(() -> service("f1").ensureFounderIsSuperUser()).doesNotThrowAnyException();
        assertThat(calls).isEmpty();
    }

    @Test
    void un_fallo_al_sincronizar_el_rol_no_se_propaga() {
        accountRoles = Optional.of(new AccountRoles(true, true));
        ensureRoleFailure = new ExternalServiceException("firestore caído", new RuntimeException());

        assertThatCode(() -> service("f1").ensureFounderIsSuperUser()).doesNotThrowAnyException();
    }

    // El rol que manda en cada request es el de la base de usuarios.
    @Test
    void el_fundador_queda_como_superuser_en_la_base_de_usuarios() {
        accountRoles = Optional.of(AccountRoles.NONE);

        service("f1").ensureFounderIsSuperUser();

        assertThat(userRoleCalls).containsExactly("f1 superuser");
    }

    @Test
    void un_fallo_al_guardar_el_rol_en_la_base_no_se_propaga() {
        accountRoles = Optional.of(new AccountRoles(true, true));
        userRoleFailure = new ExternalServiceException("postgres caído", new RuntimeException());

        assertThatCode(() -> service("f1").ensureFounderIsSuperUser()).doesNotThrowAnyException();
    }
}
