package com.areascript.taxiapp.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Interruptores del despacho por cercanía (taxiapp.dispatch.*). Los parámetros
// de la búsqueda (radios, esperas) NO van acá: los edita un administrador en la
// base (dispatch_rings / dispatch_settings).
//
// Hay dos porque el despacho se implanta en dos tiempos, por las apps viejas:
//
//   enabled        El servidor busca conductores cercanos y les deja la oferta en
//                  su bandeja. Es ADITIVO: las apps viejas siguen viendo todos los
//                  pedidos y nada cambia para ellas. Por eso va activo por defecto.
//
//   enforceOffers  Solo se puede aceptar una carrera que se te ofreció, y si
//                  nadie del último anillo acepta se cierra con "no hay
//                  vehículos". Rompe a las apps viejas (que aceptan lo que ven en
//                  taxi_requests), así que se activa DESPUÉS de subir la versión
//                  mínima de las apps.
@ConfigurationProperties(prefix = "taxiapp.dispatch")
public record DispatchProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean enforceOffers
) {
}
