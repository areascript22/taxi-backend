package com.areascript.taxiapp.driveradmin.domain;

import org.junit.jupiter.api.Test;

import static com.areascript.taxiapp.driveradmin.domain.ApprovalStatus.APPROVED;
import static com.areascript.taxiapp.driveradmin.domain.ApprovalStatus.PENDING;
import static com.areascript.taxiapp.driveradmin.domain.ApprovalStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

// La regla que decide en qué estado de aprobación queda un conductor al guardar
// su perfil. Es una función pura: sin Spring ni base de datos.
class DriverApprovalRulesTest {

    // ---- Conductor normal: la aprobación es de un administrador ----

    @Test
    void un_alta_normal_nace_pendiente() {
        assertThat(DriverApprovalRules.afterProfileSave(null, false)).isEqualTo(PENDING);
    }

    @Test
    void editar_conserva_el_estado_que_ya_tenia() {
        assertThat(DriverApprovalRules.afterProfileSave(PENDING, false)).isEqualTo(PENDING);
        assertThat(DriverApprovalRules.afterProfileSave(APPROVED, false)).isEqualTo(APPROVED);
        assertThat(DriverApprovalRules.afterProfileSave(REJECTED, false)).isEqualTo(REJECTED);
    }

    // ---- Fundador: nadie puede aprobarlo, así que no espera ----

    @Test
    void el_alta_del_fundador_nace_aprobada() {
        assertThat(DriverApprovalRules.afterProfileSave(null, true)).isEqualTo(APPROVED);
    }

    @Test
    void el_fundador_queda_aprobado_aunque_estuviera_pendiente_o_rechazado() {
        assertThat(DriverApprovalRules.afterProfileSave(PENDING, true)).isEqualTo(APPROVED);
        assertThat(DriverApprovalRules.afterProfileSave(REJECTED, true)).isEqualTo(APPROVED);
    }

    @Test
    void el_fundador_ya_aprobado_sigue_aprobado() {
        assertThat(DriverApprovalRules.afterProfileSave(APPROVED, true)).isEqualTo(APPROVED);
    }
}
