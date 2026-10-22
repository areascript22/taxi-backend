package com.areascript.taxiapp.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Interruptores y topes del despacho por cercanía (taxiapp.dispatch.*). Los
// parámetros de la búsqueda (radios, esperas) NO van acá: los edita un
// administrador en la base (dispatch_rings / dispatch_settings).
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
//
// Topes de lo que un administrador puede configurar (se validan al guardar,
// ver DispatchConfigRules). Son límites de operación, no de la base: la base
// solo impone rangos absolutos por fila.
//
//   maxRings           Cuántos anillos puede tener la búsqueda.
//   maxTotalWait       Cuánto puede durar la búsqueda completa (suma de las
//                      esperas). Alarga también el tiempo que el pasajero
//                      espera, así que no conviene que crezca sin tope.
//   minLocationMaxAge  Lo más baja que puede ser la frescura exigida a la
//                      ubicación. La app del conductor reporta cada ~20 s
//                      aunque esté quieta: por debajo de esto, los conductores
//                      parados quedarían fuera del despacho.
@ConfigurationProperties(prefix = "taxiapp.dispatch")
public record DispatchProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean enforceOffers,
        @DefaultValue("6") int maxRings,
        @DefaultValue("120s") Duration maxTotalWait,
        @DefaultValue("30s") Duration minLocationMaxAge
) {
}
