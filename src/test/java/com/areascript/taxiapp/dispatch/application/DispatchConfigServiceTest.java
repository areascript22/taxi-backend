package com.areascript.taxiapp.dispatch.application;

import com.areascript.taxiapp.characterization.support.FakeDispatchConfigStore;
import com.areascript.taxiapp.characterization.support.FakeUserAccounts;
import com.areascript.taxiapp.common.config.DispatchProperties;
import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.common.error.ConflictException;
import com.areascript.taxiapp.common.error.ForbiddenException;
import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.dispatch.application.DispatchConfigService.ChangeView;
import com.areascript.taxiapp.dispatch.application.DispatchConfigService.ConfigView;
import com.areascript.taxiapp.dispatch.application.DispatchConfigService.RingInput;
import com.areascript.taxiapp.dispatch.application.DispatchConfigService.UpdateCommand;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigChange;
import com.areascript.taxiapp.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Los casos de uso del panel de configuración del despacho, con el
// almacenamiento y las personas en memoria: lo que se prueba es QUIÉN puede
// qué, que se valide antes de guardar y que quede constancia.
class DispatchConfigServiceTest {

    private FakeDispatchConfigStore store;
    private FakeUserAccounts users;
    private DispatchConfigService service;

    @BeforeEach
    void setUp() {
        store = new FakeDispatchConfigStore();
        users = new FakeUserAccounts();
        users.seed("super", "super@test.com", "Super Uno", UserRole.SUPERUSER);
        users.seed("admin", "admin@test.com", "Admin Dos", UserRole.ADMIN);
        service = new DispatchConfigService(
                store,
                users,
                new DispatchProperties(true, true, 6, Duration.ofSeconds(120), Duration.ofSeconds(30)),
                new TaxiappProperties(
                        new TaxiappProperties.Rides(Duration.ofSeconds(35), Duration.ofSeconds(10)),
                        new TaxiappProperties.Chat(1000, 80, Duration.ofDays(7)),
                        null));
    }

    private static UpdateCommand command(long version, RingInput... rings) {
        return new UpdateCommand(List.of(rings), 45, version);
    }

    private static RingInput ring(int radius, int wait, int max) {
        return new RingInput(radius, wait, max);
    }

    // ---- Ver ----

    @Test
    void un_admin_ve_la_configuracion_pero_no_puede_editarla_con_el_interruptor_apagado() {
        ConfigView view = service.get(false);

        assertThat(view.config().plan().rings()).hasSize(3);
        assertThat(view.canEdit()).isFalse();
        assertThat(view.canChangeAdminEditing()).isFalse();
        assertThat(view.totalWait()).isEqualTo(Duration.ofSeconds(30));
        assertThat(view.requestExpiry()).isEqualTo(Duration.ofSeconds(35));
    }

    @Test
    void el_superuser_ve_que_puede_editar_y_mover_el_interruptor() {
        ConfigView view = service.get(true);

        assertThat(view.canEdit()).isTrue();
        assertThat(view.canChangeAdminEditing()).isTrue();
    }

    @Test
    void un_admin_ve_que_puede_editar_cuando_el_superuser_lo_habilito() {
        store.seedAdminsCanEdit(true);

        ConfigView view = service.get(false);

        assertThat(view.canEdit()).isTrue();
        assertThat(view.canChangeAdminEditing()).isFalse();
    }

    // ---- Editar ----

    @Test
    void el_superuser_edita_y_queda_como_autor_con_la_version_nueva() {
        ConfigView saved = service.update(command(0, ring(2000, 8, 5), ring(5000, 12, 7)), "super", true);

        assertThat(saved.config().plan().rings()).hasSize(2);
        assertThat(saved.config().plan().rings().get(0).radiusMeters()).isEqualTo(2000);
        assertThat(saved.config().version()).isEqualTo(1);
        assertThat(saved.updatedByName()).isEqualTo("Super Uno");
        assertThat(store.load().plan().rings()).hasSize(2);
    }

    @Test
    void los_anillos_toman_su_numero_de_la_posicion() {
        ConfigView saved = service.update(command(0, ring(1000, 5, 5), ring(2000, 5, 5), ring(3000, 5, 5)), "super", true);

        assertThat(saved.config().plan().rings()).extracting(r -> r.number()).containsExactly(1, 2, 3);
    }

    @Test
    void un_admin_no_puede_editar_con_el_interruptor_apagado() {
        assertThatThrownBy(() -> service.update(command(0, ring(2000, 8, 5)), "admin", false))
                .isInstanceOf(ForbiddenException.class);

        assertThat(store.load().version()).isZero();
    }

    @Test
    void un_admin_puede_editar_con_el_interruptor_encendido() {
        store.seedAdminsCanEdit(true);

        ConfigView saved = service.update(command(0, ring(2000, 8, 5)), "admin", false);

        assertThat(saved.config().plan().rings()).hasSize(1);
        assertThat(saved.updatedByName()).isEqualTo("Admin Dos");
    }

    @Test
    void apagar_el_interruptor_le_quita_al_admin_la_edicion_de_inmediato() {
        service.setAdminsCanEdit(true, "super", true);
        service.update(command(0, ring(2000, 8, 5)), "admin", false);

        service.setAdminsCanEdit(false, "super", true);

        assertThatThrownBy(() -> service.update(command(1, ring(2500, 8, 5)), "admin", false))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void un_admin_sin_permiso_no_se_entera_de_que_sus_valores_eran_invalidos() {
        // Se pregunta el permiso antes que el contenido: 403, no 400.
        assertThatThrownBy(() -> service.update(command(0, ring(5, 8, 5)), "admin", false))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void valores_invalidos_se_rechazan_sin_guardar_nada() {
        assertThatThrownBy(() -> service.update(command(0, ring(5, 8, 5)), "super", true))
                .isInstanceOf(InvalidInputException.class);

        assertThat(store.load().version()).isZero();
        assertThat(store.load().plan().rings()).hasSize(3);
    }

    @Test
    void sin_la_lista_de_anillos_es_un_400() {
        assertThatThrownBy(() -> service.update(new UpdateCommand(null, 45, 0), "super", true))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void una_version_vieja_es_un_conflicto_y_no_pisa_el_cambio_de_otro() {
        service.update(command(0, ring(2000, 8, 5)), "super", true);

        assertThatThrownBy(() -> service.update(command(0, ring(9000, 8, 5)), "super", true))
                .isInstanceOf(ConflictException.class);

        assertThat(store.load().plan().rings().get(0).radiusMeters()).isEqualTo(2000);
    }

    // ---- El interruptor ----

    @Test
    void solo_el_superuser_mueve_el_interruptor() {
        assertThatThrownBy(() -> service.setAdminsCanEdit(true, "admin", false))
                .isInstanceOf(ForbiddenException.class);

        assertThat(store.load().adminsCanEdit()).isFalse();
    }

    @Test
    void el_superuser_enciende_el_interruptor_sin_invalidar_una_edicion_en_curso() {
        ConfigView view = service.setAdminsCanEdit(true, "super", true);

        assertThat(view.config().adminsCanEdit()).isTrue();
        assertThat(view.config().version()).isZero();
        // quien venía editando con la versión 0 todavía puede guardar
        assertThat(service.update(command(0, ring(2000, 8, 5)), "super", true).config().version()).isEqualTo(1);
    }

    // ---- Historial ----

    @Test
    void cada_cambio_deja_su_entrada_con_el_autor_y_la_foto_de_antes_y_despues() {
        service.update(command(0, ring(2000, 8, 5)), "super", true);
        service.setAdminsCanEdit(true, "super", true);

        List<ChangeView> history = service.history(10, null);

        assertThat(history).hasSize(2);
        // el más nuevo primero
        assertThat(history.get(0).change().type()).isEqualTo(DispatchConfigChange.Type.ADMINS_CAN_EDIT);
        assertThat(history.get(0).change().before().adminsCanEdit()).isFalse();
        assertThat(history.get(0).change().after().adminsCanEdit()).isTrue();
        assertThat(history.get(1).change().type()).isEqualTo(DispatchConfigChange.Type.CONFIG);
        assertThat(history.get(1).change().before().rings()).hasSize(3);
        assertThat(history.get(1).change().after().rings()).hasSize(1);
        assertThat(history.get(1).changedByName()).isEqualTo("Super Uno");
    }

    @Test
    void guardar_lo_mismo_no_deja_rastro() {
        service.update(command(0, ring(3000, 10, 10), ring(6000, 10, 10), ring(9000, 10, 10)), "super", true);

        assertThat(service.history(10, null)).isEmpty();
        assertThat(store.load().version()).isZero();
    }

    @Test
    void apagar_un_interruptor_ya_apagado_no_deja_rastro() {
        service.setAdminsCanEdit(false, "super", true);

        assertThat(service.history(10, null)).isEmpty();
    }

    @Test
    void el_historial_se_pagina_hacia_atras() {
        service.update(command(0, ring(1000, 5, 5)), "super", true);
        service.update(command(1, ring(2000, 5, 5)), "super", true);
        service.update(command(2, ring(3000, 5, 5)), "super", true);

        List<ChangeView> firstPage = service.history(2, null);
        List<ChangeView> secondPage = service.history(2, firstPage.get(1).change().id());

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(secondPage.get(0).change().after().rings().get(0).radiusMeters()).isEqualTo(1000);
    }

    @Test
    void el_limite_del_historial_tiene_que_ser_razonable() {
        assertThatThrownBy(() -> service.history(0, null)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.history(51, null)).isInstanceOf(InvalidInputException.class);
    }
}
