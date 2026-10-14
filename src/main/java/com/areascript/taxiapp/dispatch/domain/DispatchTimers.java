package com.areascript.taxiapp.dispatch.domain;

import java.time.Duration;

// Puerto de salida: ejecutar algo más tarde, fuera del hilo del request.
//
// Los temporizadores viven en la memoria del proceso: si el servidor se
// reinicia en mitad de una búsqueda, esa búsqueda no sigue ampliando (la
// solicitud igual vence por su propio tiempo, ver RideTimers). Mismo límite que
// el vencimiento de solicitudes.
public interface DispatchTimers {

    void after(Duration delay, Runnable task);
}
