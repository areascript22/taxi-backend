package com.areascript.taxiapp.appversion.domain;

// Qué versión mínima de una app admite el servidor y dónde se descarga la
// actual. Una app por debajo de `minimum` no puede seguir usándose: su contrato
// con el servidor ya no es compatible.
public record VersionPolicy(AppVersion minimum, String storeUrl) {
}
