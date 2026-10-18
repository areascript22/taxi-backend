package com.areascript.taxiapp.ride.domain;

import java.util.regex.Pattern;

// Dónde se recoge al pasajero. Siempre se construye con `sanitized`: lo que
// manda el cliente no se persiste tal cual.
public record PickupLocation(double latitude, double longitude, String address, String sector) {

    // Largo máximo del sector que se persiste. El más largo del .geojson actual
    // ("Sector tanques de agua la saboya") tiene 32 caracteres, así que 80 deja
    // margen de sobra para nombres nuevos sin permitir un texto arbitrario.
    static final int MAX_SECTOR_LENGTH = 80;

    // Defensa de última línea contra Plus Codes (Open Location Code, ej.
    // "GX7Q+2X Choluteca, Honduras") en la dirección: passenger_app ya filtra
    // esto en el origen (GeocodingResultParser), pero este endpoint no puede
    // asumir que todo cliente que lo llame (versión vieja de la app, un futuro
    // panel admin, etc.) lo haga. Si un Plus Code igual llega acá, se despoja
    // antes de persistir -- driver_app ya trata una dirección vacía como
    // "Nueva carrera" en vez de leerla.
    private static final Pattern PLUS_CODE_PREFIX = Pattern.compile(
            "^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}[,\\s]*",
            Pattern.CASE_INSENSITIVE
    );

    public static PickupLocation sanitized(double latitude, double longitude, String address, String sector) {
        // El sector lo resuelve passenger_app contra sus polígonos; acá solo se
        // sanea. No se recalcula en el servidor a propósito: el .geojson
        // viviría duplicado en dos repos y se desincronizaría al primer ajuste
        // de un polígono.
        return new PickupLocation(latitude, longitude, sanitizeAddress(address), sanitizeSector(sector));
    }

    // El sector lo va a PRONUNCIAR el TTS de driver_app, así que los espacios
    // sucios importan: el .geojson trae nombres como "Tierra nueva " o "Comil "
    // con espacio no separable al final. passenger_app ya los normaliza, pero
    // este endpoint no puede asumir que todo cliente lo haga.
    //
    // El tope de largo es defensa contra un cliente que mande un texto absurdo
    // en este campo: lo que se lee en voz alta tiene que ser un nombre de zona,
    // no un párrafo.
    static String sanitizeSector(String rawSector) {
        if (rawSector == null) {
            return "";
        }

        String collapsed = rawSector.replaceAll("[\\s\\u00A0]+", " ").trim();
        return collapsed.length() > MAX_SECTOR_LENGTH
                ? collapsed.substring(0, MAX_SECTOR_LENGTH).trim()
                : collapsed;
    }

    static String sanitizeAddress(String rawAddress) {
        if (rawAddress == null) {
            return "";
        }
        return PLUS_CODE_PREFIX.matcher(rawAddress.trim()).replaceFirst("").trim();
    }
}
