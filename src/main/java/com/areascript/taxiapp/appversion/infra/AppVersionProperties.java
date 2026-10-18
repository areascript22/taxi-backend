package com.areascript.taxiapp.appversion.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

// Política de versión por app (prefijo "taxiapp.app-versions"):
//
//   taxiapp.app-versions.driver.min-version=1.0.7
//   taxiapp.app-versions.driver.store-url=https://play.google.com/store/apps/details?id=...
//   taxiapp.app-versions.passenger.min-version=...
//
// Cambiar la versión mínima NO requiere publicar nada en las apps: se cambia en
// el servidor (o por variable de entorno, ej. TAXIAPP_APPVERSIONS_DRIVER_MINVERSION)
// y se reinicia.
@ConfigurationProperties(prefix = "taxiapp")
public record AppVersionProperties(Map<String, Policy> appVersions) {

    public AppVersionProperties {
        appVersions = appVersions == null ? Map.of() : Map.copyOf(appVersions);
    }

    public record Policy(String minVersion, String storeUrl) {
    }
}
