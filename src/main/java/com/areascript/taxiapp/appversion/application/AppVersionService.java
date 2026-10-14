package com.areascript.taxiapp.appversion.application;

import com.areascript.taxiapp.appversion.domain.AppVersion;
import com.areascript.taxiapp.appversion.domain.VersionPolicies;
import com.areascript.taxiapp.appversion.domain.VersionPolicy;
import com.areascript.taxiapp.common.error.InvalidInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Dice si una versión de una app puede seguir usándose.
@Service
public class AppVersionService {

    private static final Logger log = LoggerFactory.getLogger(AppVersionService.class);

    // Resultado de la consulta. `minimumVersion` y `storeUrl` se devuelven
    // siempre, aunque la app esté al día: la pantalla de "actualiza" los usa.
    public record VersionCheck(boolean updateRequired, String minimumVersion, String storeUrl) {
    }

    private final VersionPolicies policies;

    public AppVersionService(VersionPolicies policies) {
        this.policies = policies;
    }

    // Una versión que no se puede interpretar NO bloquea a la app: el servidor
    // solo corta el paso cuando está seguro de que la versión es vieja. Es la
    // misma postura que toman las apps cuando no logran consultar.
    public VersionCheck check(String app, String clientVersion) {
        VersionPolicy policy = policies.find(app).orElseThrow(() ->
                new InvalidInputException("App desconocida: '" + app + "'. Valores permitidos: driver, passenger"));

        boolean updateRequired = AppVersion.parse(clientVersion)
                .map(version -> version.isOlderThan(policy.minimum()))
                .orElseGet(() -> {
                    log.warn("AppVersionDebug | No se pudo interpretar la versión '{}' de la app '{}'; se deja pasar",
                            clientVersion, app);
                    return false;
                });

        return new VersionCheck(updateRequired, policy.minimum().toString(), policy.storeUrl());
    }
}
