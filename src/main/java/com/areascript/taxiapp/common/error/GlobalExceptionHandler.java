package com.areascript.taxiapp.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Único lugar donde una excepción se convierte en respuesta HTTP. Antes cada
// endpoint tenía su propio try/catch con 3-5 bloques que hacían esta misma
// traducción a mano.
//
// El cuerpo sigue el estándar RFC 9457 (ProblemDetail,
// Content-Type: application/problem+json). Los códigos HTTP son exactamente
// los que devolvía cada endpoint antes; lo nuevo es que ahora traen cuerpo.
// Las apps hoy solo miran el código, así que el cuerpo no les afecta.
//
// Extender ResponseEntityExceptionHandler hace que los errores propios de
// Spring MVC (JSON mal formado, parámetro con tipo inválido, ruta inexistente)
// también respondan con ProblemDetail y su código de siempre.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Lo que ve el cliente en un 500. El detalle real (qué falló en Firebase)
    // queda en los logs: no se expone hacia afuera.
    static final String INTERNAL_ERROR_DETAIL = "Ocurrió un error interno. Intenta de nuevo más tarde.";

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ProblemDetail> handleAppException(AppException exception) {
        HttpStatus status = toHttpStatus(exception.type());

        String detail;
        if (status.is5xxServerError()) {
            // Los services ya loguean el error con su stack trace antes de
            // lanzar; acá solo se deja constancia de que terminó en 500.
            log.error("ErrorDebug | {} -> {}: {}", exception.getClass().getSimpleName(), status.value(), exception.getMessage());
            detail = INTERNAL_ERROR_DETAIL;
        } else {
            log.debug("ErrorDebug | {} -> {}: {}", exception.getClass().getSimpleName(), status.value(), exception.getMessage());
            detail = exception.getMessage();
        }

        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    // Red de seguridad: cualquier excepción no prevista (un bug) sigue
    // respondiendo 500, como lo hacía Spring por defecto, pero con el mismo
    // formato que el resto y sin filtrar detalles internos.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        log.error("ErrorDebug | Excepción no controlada: {}", exception.getMessage(), exception);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, INTERNAL_ERROR_DETAIL));
    }

    static HttpStatus toHttpStatus(ErrorType type) {
        return switch (type) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case EXTERNAL_SERVICE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
