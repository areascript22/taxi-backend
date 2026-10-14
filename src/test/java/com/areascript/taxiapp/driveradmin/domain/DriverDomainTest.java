package com.areascript.taxiapp.driveradmin.domain;

import com.areascript.taxiapp.common.error.ForbiddenException;
import com.areascript.taxiapp.identity.domain.AccountRoles;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Reglas del dominio de conductores: Java puro, sin Firebase ni Spring.
class DriverDomainTest {

    @Nested
    class ApprovalStatusTest {

        @Test
        void parsea_los_tres_estados_conocidos() {
            assertThat(ApprovalStatus.parse("pending")).contains(ApprovalStatus.PENDING);
            assertThat(ApprovalStatus.parse("approved")).contains(ApprovalStatus.APPROVED);
            assertThat(ApprovalStatus.parse("rejected")).contains(ApprovalStatus.REJECTED);
        }

        @Test
        void un_valor_desconocido_o_nulo_no_se_parsea() {
            assertThat(ApprovalStatus.parse("quizas")).isEmpty();
            assertThat(ApprovalStatus.parse("APPROVED")).isEmpty();
            assertThat(ApprovalStatus.parse(null)).isEmpty();
        }

        // Conductores anteriores al sistema de aprobación: sin el campo.
        @Test
        void un_documento_sin_estado_cuenta_como_aprobado() {
            assertThat(ApprovalStatus.fromStored(null)).isEqualTo(ApprovalStatus.APPROVED);
        }

        // Ante la duda, no aprobado.
        @Test
        void un_valor_guardado_desconocido_cuenta_como_pendiente() {
            assertThat(ApprovalStatus.fromStored("basura")).isEqualTo(ApprovalStatus.PENDING);
            assertThat(ApprovalStatus.fromStored("rejected")).isEqualTo(ApprovalStatus.REJECTED);
        }

        @Test
        void el_valor_de_red_es_el_que_espera_la_app() {
            assertThat(ApprovalStatus.APPROVED.wireValue()).isEqualTo("approved");
            assertThat(ApprovalStatus.REJECTED.wireValue()).isEqualTo("rejected");
            assertThat(ApprovalStatus.PENDING.wireValue()).isEqualTo("pending");
        }
    }

    @Nested
    class DriverRoleTest {

        @Test
        void parsea_los_roles_conocidos() {
            assertThat(DriverRole.parse("driver")).contains(DriverRole.DRIVER);
            assertThat(DriverRole.parse("admin")).contains(DriverRole.ADMIN);
            assertThat(DriverRole.parse("superuser")).contains(DriverRole.SUPERUSER);
            assertThat(DriverRole.parse("jefe")).isEmpty();
            assertThat(DriverRole.parse(null)).isEmpty();
        }

        // Un valor corrupto nunca otorga privilegios.
        @Test
        void un_rol_guardado_ausente_o_desconocido_es_un_conductor_corriente() {
            assertThat(DriverRole.fromStored(null)).isEqualTo(DriverRole.DRIVER);
            assertThat(DriverRole.fromStored("root")).isEqualTo(DriverRole.DRIVER);
            assertThat(DriverRole.fromStored("admin")).isEqualTo(DriverRole.ADMIN);
        }
    }

    @Nested
    class ModerationPolicyTest {

        private static final AccountRoles DRIVER = AccountRoles.NONE;
        private static final AccountRoles ADMIN = new AccountRoles(true, false);
        private static final AccountRoles SUPERUSER = new AccountRoles(true, true);

        @Test
        void cualquiera_puede_actuar_sobre_un_conductor_corriente() {
            assertThatCode(() -> ModerationPolicy.assertCanModerate(DRIVER, false)).doesNotThrowAnyException();
            assertThatCode(() -> ModerationPolicy.assertCanModerate(DRIVER, true)).doesNotThrowAnyException();
        }

        @Test
        void un_admin_no_puede_actuar_sobre_otro_admin() {
            assertThatThrownBy(() -> ModerationPolicy.assertCanModerate(ADMIN, false))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void un_superuser_si_puede_actuar_sobre_un_admin() {
            assertThatCode(() -> ModerationPolicy.assertCanModerate(ADMIN, true)).doesNotThrowAnyException();
        }

        // Ni siquiera otro superuser.
        @Test
        void nadie_puede_actuar_sobre_un_superuser() {
            assertThatThrownBy(() -> ModerationPolicy.assertCanModerate(SUPERUSER, false))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> ModerationPolicy.assertCanModerate(SUPERUSER, true))
                    .isInstanceOf(ForbiddenException.class);
        }
    }
}
