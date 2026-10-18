package com.areascript.taxiapp.common.error;

// Qué tipo de fallo ocurrió, en términos de negocio. GlobalExceptionHandler
// lo traduce a un código HTTP: así ninguna capa por debajo de los controllers
// necesita conocer HTTP.
public enum ErrorType {
    // El request trae datos que no tienen sentido (400).
    INVALID_INPUT,
    // Quien llama está autenticado pero no puede hacer esto (403).
    FORBIDDEN,
    // Lo que se pide no existe (404).
    NOT_FOUND,
    // La operación choca con el estado actual: carrera ya tomada, viaje ya
    // terminado, cuenta con un viaje activo... (409).
    CONFLICT,
    // Falló Firebase u otro servicio externo (500).
    EXTERNAL_SERVICE
}
