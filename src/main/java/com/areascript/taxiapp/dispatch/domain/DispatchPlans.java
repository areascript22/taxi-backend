package com.areascript.taxiapp.dispatch.domain;

// Puerto de salida: de dónde sale la configuración del despacho (hoy, las tablas
// dispatch_rings y dispatch_settings, que edita un administrador).
//
// Un fallo del almacenamiento se traduce a ExternalServiceException
// (common.error).
public interface DispatchPlans {

    // La configuración vigente ahora. Un plan vacío significa "no despachar".
    DispatchPlan current();
}
