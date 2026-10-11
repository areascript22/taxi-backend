package com.areascript.taxiapp.ride.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PickupLocationTest {

    // El sector que se persiste acá lo PRONUNCIA el TTS de driver_app, así que
    // lo que entra sucio se escucha mal.
    @Nested
    class Sector {

        @Test
        void deja_pasar_un_sector_normal_sin_tocarlo() {
            assertThat(PickupLocation.sanitizeSector("La Condamine")).isEqualTo("La Condamine");
            assertThat(PickupLocation.sanitizeSector("Loma de Quito")).isEqualTo("Loma de Quito");
        }

        // Una app vieja no manda el campo: se persiste vacío y driver_app vuelve
        // a leer la dirección completa, como antes de que existieran los sectores.
        @Test
        void null_y_vacio_se_persisten_como_cadena_vacia() {
            assertThat(PickupLocation.sanitizeSector(null)).isEmpty();
            assertThat(PickupLocation.sanitizeSector("")).isEmpty();
            assertThat(PickupLocation.sanitizeSector("   ")).isEmpty();
        }

        // Los nombres del .geojson real traen esta basura: "Tierra nueva " con
        // espacio al final, "Comil " con espacio no separable, y
        // "Los alamos  - Sesquicentenario" con doble espacio.
        @Test
        void normaliza_espacios_sucios_incluido_el_no_separable() {
            assertThat(PickupLocation.sanitizeSector("Tierra nueva ")).isEqualTo("Tierra nueva");
            assertThat(PickupLocation.sanitizeSector("Comil ")).isEqualTo("Comil");
            assertThat(PickupLocation.sanitizeSector("Los alamos  - Sesquicentenario"))
                    .isEqualTo("Los alamos - Sesquicentenario");
            assertThat(PickupLocation.sanitizeSector("\n Mercado  Mayorista \t"))
                    .isEqualTo("Mercado Mayorista");
        }

        @Test
        void conserva_los_acentos() {
            assertThat(PickupLocation.sanitizeSector("Colegio Isabel de Godín"))
                    .isEqualTo("Colegio Isabel de Godín");
        }

        // Defensa contra un cliente que use este campo para mandar un texto
        // arbitrario: lo que se lee en voz alta debe ser un nombre de zona.
        @Test
        void recorta_un_sector_absurdamente_largo() {
            assertThat(PickupLocation.sanitizeSector("a".repeat(500))).hasSize(80);
        }
    }

    @Nested
    class Direccion {

        @Test
        void quita_un_plus_code_del_inicio() {
            assertThat(PickupLocation.sanitizeAddress("GX7Q+2X Riobamba, Ecuador")).isEqualTo("Riobamba, Ecuador");
            assertThat(PickupLocation.sanitizeAddress("gx7q+2x, Riobamba")).isEqualTo("Riobamba");
        }

        @Test
        void deja_una_direccion_normal_sin_tocarla() {
            assertThat(PickupLocation.sanitizeAddress("Av. Daniel León Borja")).isEqualTo("Av. Daniel León Borja");
        }

        @Test
        void una_direccion_ausente_es_cadena_vacia() {
            assertThat(PickupLocation.sanitizeAddress(null)).isEmpty();
        }

        @Test
        void una_direccion_que_es_solo_un_plus_code_queda_vacia() {
            assertThat(PickupLocation.sanitizeAddress("GX7Q+2X")).isEmpty();
        }
    }

    @Test
    void sanitized_arma_la_ubicacion_con_los_dos_campos_saneados() {
        PickupLocation location = PickupLocation.sanitized(-1.66, -78.65, "GX7Q+2X Centro", "  La  Saboya ");

        assertThat(location.latitude()).isEqualTo(-1.66);
        assertThat(location.longitude()).isEqualTo(-78.65);
        assertThat(location.address()).isEqualTo("Centro");
        assertThat(location.sector()).isEqualTo("La Saboya");
    }
}
