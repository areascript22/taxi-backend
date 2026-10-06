package com.areascript.taxiapp.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// El sector que se persiste acá lo PRONUNCIA el TTS de driver_app, así que lo
// que entra sucio se escucha mal.
class RideServiceSectorTest {

    @Test
    void deja_pasar_un_sector_normal_sin_tocarlo() {
        assertThat(RideService.sanitizeSector("La Condamine")).isEqualTo("La Condamine");
        assertThat(RideService.sanitizeSector("Loma de Quito")).isEqualTo("Loma de Quito");
    }

    // Una app vieja no manda el campo: se persiste vacío y driver_app vuelve a
    // leer la dirección completa, como antes de que existieran los sectores.
    @Test
    void null_y_vacio_se_persisten_como_cadena_vacia() {
        assertThat(RideService.sanitizeSector(null)).isEmpty();
        assertThat(RideService.sanitizeSector("")).isEmpty();
        assertThat(RideService.sanitizeSector("   ")).isEmpty();
    }

    // Los nombres del .geojson real traen esta basura: "Tierra nueva " con
    // espacio al final, "Comil " con espacio no separable, y
    // "Los alamos  - Sesquicentenario" con doble espacio.
    @Test
    void normaliza_espacios_sucios_incluido_el_no_separable() {
        assertThat(RideService.sanitizeSector("Tierra nueva ")).isEqualTo("Tierra nueva");
        assertThat(RideService.sanitizeSector("Comil ")).isEqualTo("Comil");
        assertThat(RideService.sanitizeSector("Los alamos  - Sesquicentenario"))
                .isEqualTo("Los alamos - Sesquicentenario");
        assertThat(RideService.sanitizeSector("\n Mercado  Mayorista \t"))
                .isEqualTo("Mercado Mayorista");
    }

    @Test
    void conserva_los_acentos() {
        assertThat(RideService.sanitizeSector("Colegio Isabel de Godín"))
                .isEqualTo("Colegio Isabel de Godín");
    }

    // Defensa contra un cliente que use este campo para mandar un texto
    // arbitrario: lo que se lee en voz alta debe ser un nombre de zona.
    @Test
    void recorta_un_sector_absurdamente_largo() {
        String absurdo = "a".repeat(500);

        assertThat(RideService.sanitizeSector(absurdo)).hasSize(80);
    }
}
