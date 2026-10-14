package com.areascript.taxiapp.dispatch.domain;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

// Puerto de salida: dónde están los conductores y si pueden recibir un pedido.
//
// "Disponible" es la conjunción de tres cosas, y cada una la pone alguien
// distinto:
//   en línea  -> el conductor, con el interruptor de la app
//   en carrera -> el servidor, al aceptar y al terminar una carrera
//   ubicación reciente -> el conductor, al reportarla cada pocos segundos
//
// Todo se identifica por el uid de Firebase. Un fallo del almacenamiento se
// traduce a ExternalServiceException (common.error).
public interface DriverPresence {

    // Estado de presencia; vacío si la persona no tiene perfil de conductor.
    Optional<PresenceState> state(String driverUid);

    // Cambia el interruptor. Al desconectarse se borra su última ubicación (no se
    // guarda el rastro de alguien que ya no está trabajando). Devuelve false si no
    // tiene perfil de conductor.
    boolean setOnline(String driverUid, boolean online);

    // Lo llama el servidor cuando una carrera empieza o termina. No falla si no
    // existe el perfil: el efecto es el mismo, no hay a quién marcar.
    void setOnRide(String driverUid, boolean onRide);

    // Guarda su posición SOLO si está en línea y no en carrera, en una única
    // sentencia (es el camino caliente: se llama cada pocos segundos por
    // conductor). Devuelve false si no se guardó; quien llama pregunta state()
    // para saber por qué.
    boolean recordLocationIfAvailable(String driverUid, double latitude, double longitude);

    // Los conductores disponibles a menos de `radiusMeters` del punto, del más
    // cercano al más lejano, con ubicación de no más de `maxAge`. `excludeUids`
    // permite no repetir a los que ya recibieron la oferta en un anillo anterior.
    List<NearbyDriver> findAvailableNear(
            double latitude,
            double longitude,
            int radiusMeters,
            Duration maxAge,
            int limit,
            Collection<String> excludeUids);
}
