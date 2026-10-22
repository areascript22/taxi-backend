package com.areascript.taxiapp.dispatch.domain;

import java.time.Duration;

// Hasta dónde puede llegar lo que un administrador configura.
//
// Hay dos clases de límite:
//   - ABSOLUTOS por campo (radio, espera, conductores, frescura máxima): son los
//     mismos CHECK de las tablas (V4). Se validan acá para contestar 400 con un
//     mensaje útil y no un 500 por violar la restricción.
//   - De OPERACIÓN (cuántos anillos, cuánto dura la búsqueda, frescura mínima):
//     vienen de la configuración (taxiapp.dispatch.*) y se pueden ajustar sin
//     tocar la base.
//
// La app los recibe en la respuesta del GET para validar el formulario con los
// mismos números, sin copiarlos.
public record DispatchConfigLimits(
        int maxRings,
        Duration maxTotalWait,
        Duration minLocationMaxAge
) {

    // Los CHECK de dispatch_rings y dispatch_settings.
    public static final int MIN_RADIUS_METERS = 100;
    public static final int MAX_RADIUS_METERS = 50_000;
    public static final int MIN_WAIT_SECONDS = 1;
    public static final int MAX_WAIT_SECONDS = 120;
    public static final int MIN_MAX_DRIVERS = 1;
    public static final int MAX_MAX_DRIVERS = 100;
    public static final int MAX_LOCATION_MAX_AGE_SECONDS = 600;
}
