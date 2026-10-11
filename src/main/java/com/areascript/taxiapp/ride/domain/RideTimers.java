package com.areascript.taxiapp.ride.domain;

import java.time.Duration;

// Puerto de salida: tareas diferidas del ciclo de vida de una carrera. Hoy
// viven en memoria del proceso: si el servidor se reinicia, las que estaban
// pendientes se pierden (por eso el servicio tiene que estar siempre vivo; ver
// deploy/taxiapp.service). Hacerlas persistentes es trabajo de una fase
// posterior y solo cambia la implementación de este puerto.
public interface RideTimers {

    // Vencimiento de una solicitud sin conductor.
    void expireAfter(Duration delay, Runnable task);

    // Limpieza del nodo una vez que la carrera terminó.
    void cleanupAfter(Duration delay, Runnable task);
}
