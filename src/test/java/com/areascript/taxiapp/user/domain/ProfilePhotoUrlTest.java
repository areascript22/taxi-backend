package com.areascript.taxiapp.user.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// La URL de la foto de perfil solo vale si es la foto de ESA persona: el
// servidor no recibe el archivo y no puede confiar en lo que mande el cliente.
class ProfilePhotoUrlTest {

    private static final String BUCKET = "taxigo-dev-96d02.firebasestorage.app";
    private static final String FOLDER = "driver_profile_photos";
    private static final String UID = "uid-123";

    // Lo que devuelve getDownloadURL() de Firebase Storage.
    private static String url(String bucket, String objectName) {
        return "https://firebasestorage.googleapis.com/v0/b/" + bucket + "/o/" + objectName
                + "?alt=media&token=abc-def";
    }

    private static boolean valid(String url) {
        return ProfilePhotoUrl.belongsTo(url, BUCKET, FOLDER, UID);
    }

    @Test
    void la_url_de_descarga_de_su_propia_foto_es_valida() {
        assertThat(valid(url(BUCKET, "driver_profile_photos%2Fuid-123.jpg"))).isTrue();
    }

    // Firebase SIEMPRE codifica la barra del nombre del objeto (%2F). Aceptar la
    // forma sin codificar solo agregaría una segunda manera de escribir la misma
    // ruta, y cada ambigüedad en una validación de seguridad es un riesgo.
    @Test
    void sin_codificar_la_barra_no_es_valida() {
        assertThat(valid(url(BUCKET, "driver_profile_photos/uid-123.jpg"))).isFalse();
    }

    @Test
    void la_foto_de_otra_persona_no_es_valida() {
        assertThat(valid(url(BUCKET, "driver_profile_photos%2Fotro-uid.jpg"))).isFalse();
    }

    @Test
    void la_carpeta_del_otro_tipo_de_perfil_no_es_valida() {
        // Un conductor no puede apuntar a la foto de pasajero (ni al revés).
        assertThat(valid(url(BUCKET, "passenger_profile_photos%2Fuid-123.jpg"))).isFalse();
    }

    @Test
    void un_bucket_ajeno_no_es_valido() {
        assertThat(valid(url("otro-proyecto.firebasestorage.app", "driver_profile_photos%2Fuid-123.jpg")))
                .isFalse();
    }

    @Test
    void un_sitio_externo_no_es_valido() {
        assertThat(valid("https://evil.example.com/driver_profile_photos/uid-123.jpg")).isFalse();
        assertThat(valid("https://evil.example.com/v0/b/" + BUCKET + "/o/driver_profile_photos%2Fuid-123.jpg"))
                .isFalse();
    }

    @Test
    void exige_https() {
        assertThat(valid(url(BUCKET, "driver_profile_photos%2Fuid-123.jpg").replace("https://", "http://")))
                .isFalse();
    }

    @Test
    void no_acepta_credenciales_embebidas_ni_otro_puerto() {
        assertThat(valid("https://user:pass@firebasestorage.googleapis.com/v0/b/" + BUCKET
                + "/o/driver_profile_photos%2Fuid-123.jpg")).isFalse();
        assertThat(valid("https://firebasestorage.googleapis.com:8443/v0/b/" + BUCKET
                + "/o/driver_profile_photos%2Fuid-123.jpg")).isFalse();
    }

    @Test
    void no_se_deja_colar_otra_ruta_con_el_nombre_al_final() {
        // El objeto tiene que ser EXACTAMENTE carpeta/uid.jpg.
        assertThat(valid(url(BUCKET, "driver_profile_photos%2Fuid-123.jpg.exe"))).isFalse();
        assertThat(valid(url(BUCKET, "otra%2Fdriver_profile_photos%2Fuid-123.jpg"))).isFalse();
        assertThat(valid(url(BUCKET, "driver_profile_photos%2F..%2Fuid-123.jpg"))).isFalse();
    }

    @Test
    void basura_o_vacio_no_es_valido() {
        assertThat(valid(null)).isFalse();
        assertThat(valid("")).isFalse();
        assertThat(valid("   ")).isFalse();
        assertThat(valid("no es una url")).isFalse();
        assertThat(valid("https://")).isFalse();
    }

    @Test
    void sin_bucket_configurado_se_comprueban_host_carpeta_y_uid() {
        assertThat(ProfilePhotoUrl.belongsTo(
                url("cualquiera", "driver_profile_photos%2Fuid-123.jpg"), null, FOLDER, UID)).isTrue();
        assertThat(ProfilePhotoUrl.belongsTo(
                url("cualquiera", "driver_profile_photos%2Fotro.jpg"), null, FOLDER, UID)).isFalse();
    }
}
