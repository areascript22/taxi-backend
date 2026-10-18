package com.areascript.taxiapp.dispatch.domain;

// Hecho: se recorrieron todos los anillos y nadie aceptó. Lo publica el
// despacho y lo escucha la feature de carreras, que es la dueña del estado de la
// carrera y decide cerrarla (el despacho no la toca).
//
// Solo se publica cuando el despacho es la ÚNICA vía para conseguir conductor
// (ver DispatchProperties.enforceOffers): mientras las apps viejas sigan viendo
// todos los pedidos, "nadie cercano aceptó" no significa "nadie va a aceptar".
public record NoDriversAvailable(String rideId, String passengerId) {
}
