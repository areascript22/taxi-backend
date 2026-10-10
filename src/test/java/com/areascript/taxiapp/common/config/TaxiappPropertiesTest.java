package com.areascript.taxiapp.common.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxiappPropertiesTest {

    private static final TaxiappProperties.Rides RIDES =
            new TaxiappProperties.Rides(Duration.ofSeconds(35), Duration.ofSeconds(10));
    private static final TaxiappProperties.Chat CHAT =
            new TaxiappProperties.Chat(1000, 80, Duration.ofDays(7));

    // ---- Fundador ----

    @Test
    void el_fundador_se_reconoce_por_uid() {
        TaxiappProperties.Admin admin = new TaxiappProperties.Admin("uid-1", "a@b.com");

        assertThat(admin.hasFounder()).isTrue();
        assertThat(admin.isFounder("uid-1")).isTrue();
        assertThat(admin.isFounder("uid-2")).isFalse();
    }

    @Test
    void para_actuar_como_fundador_tambien_tiene_que_coincidir_el_email() {
        TaxiappProperties.Admin admin = new TaxiappProperties.Admin("uid-1", "a@b.com");

        assertThat(admin.isFounder("uid-1", "A@B.com")).isTrue();
        assertThat(admin.isFounder("uid-1", "otro@b.com")).isFalse();
        assertThat(admin.isFounder("uid-1", null)).isFalse();
        assertThat(admin.isFounder("uid-2", "a@b.com")).isFalse();
    }

    // Sin configurar, nadie es el fundador (ni siquiera un uid vacío).
    @Test
    void sin_configurar_no_hay_fundador() {
        TaxiappProperties.Admin blank = new TaxiappProperties.Admin("", "");
        TaxiappProperties.Admin missing = new TaxiappProperties.Admin(null, null);

        assertThat(blank.hasFounder()).isFalse();
        assertThat(blank.isFounder("")).isFalse();
        assertThat(blank.isFounder("", "")).isFalse();
        assertThat(missing.hasFounder()).isFalse();
        assertThat(missing.isFounder(null, null)).isFalse();
    }

    @Test
    void el_fundador_con_uid_pero_sin_email_no_puede_actuar_por_email() {
        TaxiappProperties.Admin admin = new TaxiappProperties.Admin("uid-1", " ");

        assertThat(admin.isFounder("uid-1")).isTrue();
        assertThat(admin.isFounder("uid-1", " ")).isFalse();
    }

    // ---- Validación al arrancar ----

    @Test
    void sin_grupo_admin_se_asume_sin_fundador() {
        TaxiappProperties properties = new TaxiappProperties(RIDES, CHAT, null);

        assertThat(properties.admin().hasFounder()).isFalse();
    }

    @Test
    void falta_un_grupo_obligatorio_y_el_error_dice_cual() {
        assertThatThrownBy(() -> new TaxiappProperties(null, CHAT, null))
                .hasMessageContaining("taxiapp.rides");
        assertThatThrownBy(() -> new TaxiappProperties(RIDES, null, null))
                .hasMessageContaining("taxiapp.chat");
        assertThatThrownBy(() -> new TaxiappProperties.Rides(null, Duration.ofSeconds(10)))
                .hasMessageContaining("pending-request-expiry");
    }

    @Test
    void los_largos_del_chat_tienen_que_ser_positivos() {
        assertThatThrownBy(() -> new TaxiappProperties.Chat(0, 80, Duration.ofDays(7)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaxiappProperties.Chat(1000, -1, Duration.ofDays(7)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
