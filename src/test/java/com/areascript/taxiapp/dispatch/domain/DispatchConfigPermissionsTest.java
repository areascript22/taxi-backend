package com.areascript.taxiapp.dispatch.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Quién puede editar la configuración del despacho: el superuser siempre, un
// admin solo si el superuser encendió el interruptor. El interruptor solo lo
// mueve el superuser.
class DispatchConfigPermissionsTest {

    @Test
    void el_superuser_edita_con_el_interruptor_apagado_o_encendido() {
        assertThat(DispatchConfigPermissions.canEdit(true, false)).isTrue();
        assertThat(DispatchConfigPermissions.canEdit(true, true)).isTrue();
    }

    @Test
    void un_admin_edita_solo_si_el_interruptor_esta_encendido() {
        assertThat(DispatchConfigPermissions.canEdit(false, true)).isTrue();
        assertThat(DispatchConfigPermissions.canEdit(false, false)).isFalse();
    }

    @Test
    void solo_el_superuser_mueve_el_interruptor() {
        assertThat(DispatchConfigPermissions.canChangeAdminEditing(true)).isTrue();
        assertThat(DispatchConfigPermissions.canChangeAdminEditing(false)).isFalse();
    }
}
