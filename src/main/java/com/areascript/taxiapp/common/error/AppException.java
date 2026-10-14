package com.areascript.taxiapp.common.error;

// Base de todas las excepciones que el servidor lanza a propósito. Cada
// subclase fija su ErrorType; GlobalExceptionHandler la convierte en la
// respuesta HTTP, así que los controllers no necesitan try/catch.
//
// Java puro a propósito (sin Spring): la pueden lanzar también las capas
// domain/application de la arquitectura nueva.
public abstract class AppException extends RuntimeException {

    protected AppException(String message) {
        super(message);
    }

    protected AppException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract ErrorType type();
}
