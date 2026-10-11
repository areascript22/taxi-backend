package com.areascript.taxiapp.driveradmin.application;

import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.driveradmin.domain.DriverRepository;
import com.areascript.taxiapp.driveradmin.domain.DriverRole;
import com.areascript.taxiapp.identity.domain.AccountRoles;
import com.areascript.taxiapp.identity.domain.IdentityProvider;
import com.areascript.taxiapp.user.domain.UserAccounts;
import com.areascript.taxiapp.user.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

// Garantiza que la cuenta dueña del proyecto tenga el claim 'superuser' (y
// admin=true) y el campo role='superuser' en su documento. Se ejecuta en cada
// arranque (ver SuperUserBootstrap) y es idempotente: si el claim ya está
// asignado no lo vuelve a setear, pero siempre re-sincroniza el campo 'role'
// por si una corrida anterior falló a mitad de camino.
//
// Nunca impide el arranque: cualquier problema se registra y se sigue. Si el
// uid no existe en el proyecto de Firebase activo (ej. al correr con el perfil
// 'prod', que usa un proyecto distinto al de dev), es solo una advertencia.
@Service
public class FounderBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(FounderBootstrapService.class);

    private final IdentityProvider identityProvider;
    private final DriverRepository drivers;
    private final UserAccounts userAccounts;
    private final TaxiappProperties.Admin adminSettings;

    public FounderBootstrapService(
            IdentityProvider identityProvider,
            DriverRepository drivers,
            UserAccounts userAccounts,
            TaxiappProperties properties
    ) {
        this.identityProvider = identityProvider;
        this.drivers = drivers;
        this.userAccounts = userAccounts;
        this.adminSettings = properties.admin();
    }

    public void ensureFounderIsSuperUser() {
        // El uid del fundador sale de taxiapp.admin.founder-uid. Si no está
        // configurado se omite todo: el claim ya asignado en Firebase sigue
        // vigente.
        if (!adminSettings.hasFounder()) {
            log.warn("SuperUserBootstrapDebug | taxiapp.admin.founder-uid no está configurado, se omite el bootstrap de superuser");
            return;
        }
        String founderUid = adminSettings.founderUid();

        Optional<AccountRoles> roles;
        try {
            roles = identityProvider.findRoles(founderUid);
        } catch (RuntimeException e) {
            log.error("SuperUserBootstrapDebug | Error al consultar uid={}: {}", founderUid, e.getMessage(), e);
            return;
        }
        if (roles.isEmpty()) {
            log.warn("SuperUserBootstrapDebug | uid={} no existe en este proyecto de Firebase, se omite el bootstrap de superuser", founderUid);
            return;
        }

        if (!roles.get().superUser()) {
            try {
                identityProvider.setRoles(founderUid, true, true);
                log.info("SuperUserBootstrapDebug | uid={} promovido a superuser correctamente", founderUid);
            } catch (RuntimeException e) {
                log.error("SuperUserBootstrapDebug | Error al promover a superuser uid={}: {}", founderUid, e.getMessage(), e);
                return;
            }
        }

        // Siempre se sincroniza, incluso si el claim ya estaba asignado en una
        // corrida anterior: si esa corrida falló después de setear el claim
        // pero antes de escribir el rol, este paso es el que lo corrige sin
        // depender de reintentar el bloque de arriba.
        try {
            drivers.ensureRole(founderUid, DriverRole.SUPERUSER);
        } catch (RuntimeException e) {
            log.error("SuperUserBootstrapDebug | Error al sincronizar role=superuser para uid={}: {}", founderUid, e.getMessage(), e);
        }

        // Y en la base de usuarios, que es la que decide los permisos en cada
        // request. Idempotente, igual que el paso anterior.
        try {
            userAccounts.setRole(founderUid, UserRole.SUPERUSER);
        } catch (RuntimeException e) {
            log.error("SuperUserBootstrapDebug | Error al sincronizar el rol superuser en la base para uid={}: {}", founderUid, e.getMessage(), e);
        }
    }
}
