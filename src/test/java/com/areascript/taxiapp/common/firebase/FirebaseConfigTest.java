package com.areascript.taxiapp.common.firebase;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Mensajes de error al cargar la credencial de Firebase: el caso típico es
// arrancar el servidor desde otra carpeta y que la ruta relativa no resuelva.
class FirebaseConfigTest {

    @Test
    void una_ruta_inexistente_dice_donde_se_busco_y_desde_que_carpeta() {
        FirebaseProperties.Credentials credentials =
                new FirebaseProperties.Credentials("file:./no-existe/credencial.json", null);

        assertThatThrownBy(() -> FirebaseConfig.loadCredentials(credentials))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No se encontró la credencial de Firebase")
                .hasMessageContaining("file:./no-existe/credencial.json")
                .hasMessageContaining(Path.of("no-existe", "credencial.json").toString())
                .hasMessageContaining(Path.of("").toAbsolutePath().toString())
                .hasMessageContaining("Working directory");
    }

    @Test
    void sin_ruta_ni_json_pide_configurar_alguna_de_las_dos() {
        FirebaseProperties.Credentials credentials = new FirebaseProperties.Credentials("  ", null);

        assertThatThrownBy(() -> FirebaseConfig.loadCredentials(credentials))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREBASE_CREDENTIALS_PATH")
                .hasMessageContaining("FIREBASE_CREDENTIALS_JSON");
    }

    @Test
    void un_json_invalido_en_la_variable_de_entorno_lo_dice() {
        FirebaseProperties.Credentials credentials = new FirebaseProperties.Credentials(null, "{no es json");

        assertThatThrownBy(() -> FirebaseConfig.loadCredentials(credentials))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREBASE_CREDENTIALS_JSON");
    }

    @Test
    void el_bucket_se_normaliza_sin_el_prefijo_gs() {
        assertThat(new FirebaseProperties.Storage("gs://taxigo-dev.firebasestorage.app").bucketName())
                .isEqualTo("taxigo-dev.firebasestorage.app");
        assertThat(new FirebaseProperties.Storage(" taxigo-dev.firebasestorage.app ").bucketName())
                .isEqualTo("taxigo-dev.firebasestorage.app");
    }
}
