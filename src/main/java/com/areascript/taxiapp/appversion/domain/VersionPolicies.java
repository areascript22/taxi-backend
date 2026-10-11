package com.areascript.taxiapp.appversion.domain;

import java.util.Optional;

// Puerto de salida: de dónde sale la política de versión de cada app. Hoy es
// configuración del servidor; podría ser una tabla o un servicio remoto sin
// que cambie el caso de uso.
public interface VersionPolicies {

    // Vacío si el servidor no conoce esa app ("driver", "passenger").
    Optional<VersionPolicy> find(String app);
}
