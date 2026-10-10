package com.areascript.taxiapp.common.error;

// Falló Firebase (o cualquier otro servicio externo) y la operación no se pudo
// completar. El mensaje es para los logs: al cliente le llega un texto genérico
// (ver GlobalExceptionHandler).
public class ExternalServiceException extends AppException {

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public ErrorType type() {
        return ErrorType.EXTERNAL_SERVICE;
    }
}
