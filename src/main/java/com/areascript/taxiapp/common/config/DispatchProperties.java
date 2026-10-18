package com.areascript.taxiapp.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Interruptores del despacho por cercanía (taxiapp.dispatch.*). Los parámetros
// de la búsqueda (radios, esperas) NO van acá: los edita un administrador en la
// base (dispatch_rings / dispatch_settings).
//
//   enabled        El servidor busca conductores cercanos y les deja la oferta en
//                  su bandeja (driver_offers/{uid}).
//
//   enforceOffers  Solo se puede aceptar una carrera que se te ofreció, y si
//                  nadie del último anillo acepta se cierra con "no hay
//                  vehículos". Es el modo normal: sin él, un conductor que no
//                  recibió la oferta podría quedarse con la carrera
//                  adivinando el passengerId.
//
// Los dos van activos por defecto. enforceOffers existe como interruptor para
// poder apagarlo si alguna vez hay que convivir con una versión de la app que
// todavía lee todos los pedidos de taxi_requests (no es el caso mientras la app
// no esté publicada).
@ConfigurationProperties(prefix = "taxiapp.dispatch")
public record DispatchProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean enforceOffers
) {
}
