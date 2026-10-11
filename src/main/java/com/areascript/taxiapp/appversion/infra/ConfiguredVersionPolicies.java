package com.areascript.taxiapp.appversion.infra;

import com.areascript.taxiapp.appversion.domain.AppVersion;
import com.areascript.taxiapp.appversion.domain.VersionPolicies;
import com.areascript.taxiapp.appversion.domain.VersionPolicy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// Las políticas salen de la configuración. Se validan al construir: una versión
// mínima mal escrita tumba el arranque con un mensaje claro, en vez de dejar
// pasar a todo el mundo en silencio (que es lo que pasaría si se ignorara).
@Component
public class ConfiguredVersionPolicies implements VersionPolicies {

    private final Map<String, VersionPolicy> policies = new HashMap<>();

    public ConfiguredVersionPolicies(AppVersionProperties properties) {
        properties.appVersions().forEach((app, policy) -> {
            AppVersion minimum = AppVersion.parse(policy.minVersion()).orElseThrow(() ->
                    new IllegalStateException("taxiapp.app-versions." + app + ".min-version no es una versión válida: '"
                            + policy.minVersion() + "' (se espera algo como 1.0.7)"));
            if (policy.storeUrl() == null || policy.storeUrl().isBlank()) {
                throw new IllegalStateException("Falta taxiapp.app-versions." + app + ".store-url");
            }
            policies.put(app, new VersionPolicy(minimum, policy.storeUrl()));
        });
    }

    @Override
    public Optional<VersionPolicy> find(String app) {
        return Optional.ofNullable(policies.get(app));
    }
}
