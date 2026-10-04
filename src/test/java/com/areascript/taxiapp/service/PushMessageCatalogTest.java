package com.areascript.taxiapp.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

// Equivalente server-side de arb_parity_test.dart: el copy de los push vive
// en properties y nada impide que una traducción se quede atrás.
class PushMessageCatalogTest {

    // Misma configuración que arma Spring Boot a partir de
    // application.properties -- si eso cambia, estos tests dejan de
    // representar producción.
    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    // Properties.load(InputStream) asume ISO-8859-1 y convertiría los
    // acentos en basura: hay que pasarle un Reader UTF-8.
    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = PushMessageCatalogTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("falta el bundle %s", resource).isNotNull();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }

    // Se leen los archivos crudos y no ResourceBundle a propósito:
    // messages.properties es el PADRE de messages_en.properties, así que
    // bundle.keySet() devuelve la unión de los dos y una key inglesa faltante
    // pasaría desapercibida (saldría en español, que es justo el bug que este
    // test busca).
    @Test
    void los_dos_bundles_tienen_exactamente_las_mismas_keys() throws IOException {
        Properties spanish = load("/messages.properties");
        Properties english = load("/messages_en.properties");

        assertThat(english.stringPropertyNames())
                .as("keys de messages_en.properties")
                .containsExactlyInAnyOrderElementsOf(spanish.stringPropertyNames());
    }

    @Test
    void ninguna_traduccion_queda_vacia() throws IOException {
        for (String bundle : new String[]{"/messages.properties", "/messages_en.properties"}) {
            Properties properties = load(bundle);
            for (String key : properties.stringPropertyNames()) {
                assertThat(properties.getProperty(key).trim())
                        .as("%s -> %s", bundle, key)
                        .isNotEmpty();
            }
        }
    }

    @Test
    void resuelve_el_copy_en_el_idioma_pedido() {
        ResourceBundleMessageSource source = messageSource();

        assertThat(source.getMessage("push.ride.accepted.title", null, Locale.forLanguageTag("es")))
                .isEqualTo("¡Carrera aceptada!");
        assertThat(source.getMessage("push.ride.accepted.title", null, Locale.forLanguageTag("en")))
                .isEqualTo("Ride accepted!");
    }

    // El caso que justifica fallback-to-system-locale=false: un idioma que no
    // traducimos tiene que caer al bundle por defecto (español) y no al locale
    // de la JVM del servidor, que no tiene nada que ver con el del usuario.
    @Test
    void un_idioma_sin_traduccion_cae_al_espaniol() {
        ResourceBundleMessageSource source = messageSource();

        assertThat(source.getMessage("push.ride.accepted.body", null, Locale.forLanguageTag("pt")))
                .isEqualTo("Un conductor va en camino a recogerte");
        assertThat(source.getMessage("push.ride.accepted.body", null, Locale.ROOT))
                .isEqualTo("Un conductor va en camino a recogerte");
    }
}
