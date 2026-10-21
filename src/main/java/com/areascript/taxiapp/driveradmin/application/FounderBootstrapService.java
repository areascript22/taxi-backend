package com.areascript.taxiapp.driveradmin.application;

import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.DriverApprovalRules;
import com.areascript.taxiapp.driveradmin.domain.DriverRepository;
import com.areascript.taxiapp.driveradmin.domain.DriverRole;
import com.areascript.taxiapp.user.domain.UserAccounts;
import com.areascript.taxiapp.user.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Garantiza que la cuenta dueña del proyecto tenga el rol 'superuser' en la
// base de usuarios (users.role_code), que es la que decide los permisos en
// cada request, y que su perfil de conductor, si ya existe, esté aprobado.
// Se ejecuta en cada arranque (ver SuperUserBootstrap) y es idempotente.
//
// Nunca impide el arranque: cualquier problema se registra y se sigue.
@Service
public class FounderBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(FounderBootstrapService.class);

    private final DriverRepository drivers;
    private final UserAccounts userAccounts;
    private final TaxiappProperties.Admin adminSettings;

    public FounderBootstrapService(
            DriverRepository drivers,
            UserAccounts userAccounts,
            TaxiappProperties properties
    ) {
        this.drivers = drivers;
        this.userAccounts = userAccounts;
        this.adminSettings = properties.admin();
    }

    public void ensureFounderIsSuperUser() {
        // El uid del fundador sale de taxiapp.admin.founder-uid. Si no está
        // configurado se omite todo: el rol ya guardado sigue vigente.
        if (!adminSettings.hasFounder()) {
            log.warn("SuperUserBootstrapDebug | taxiapp.admin.founder-uid no está configurado, se omite el bootstrap de superuser");
            return;
        }
        String founderUid = adminSettings.founderUid();

        // Los dos pasos son independientes: si uno falla, el otro se intenta
        // igual y el siguiente arranque corrige lo que haya quedado.
        try {
            drivers.ensureRole(founderUid, DriverRole.SUPERUSER);
        } catch (RuntimeException e) {
            log.error("SuperUserBootstrapDebug | Error al sincronizar role=superuser para uid={}: {}", founderUid, e.getMessage(), e);
        }

        try {
            userAccounts.setRole(founderUid, UserRole.SUPERUSER);
            log.info("SuperUserBootstrapDebug | uid={} es superuser", founderUid);
        } catch (RuntimeException e) {
            log.error("SuperUserBootstrapDebug | Error al guardar el rol superuser para uid={}: {}", founderUid, e.getMessage(), e);
        }

        approveFounderDriverProfile(founderUid);
    }

    // Si el fundador ya registró su perfil de conductor (por ejemplo en una base
    // recreada o restaurada, o antes de que existiera esta regla), no se queda
    // esperando una aprobación que nadie puede darle. Si todavía no lo registró,
    // no hay nada que hacer: nacerá aprobado al guardarlo (ver
    // DriverApprovalRules y ProfileRegistrationService).
    //
    // Va directo al repositorio y no por el caso de uso de moderación: esa vía
    // prohíbe a propósito aprobar a un superusuario, y esa regla no se toca.
    private void approveFounderDriverProfile(String founderUid) {
        try {
            drivers.findById(founderUid).ifPresent(driver -> {
                ApprovalStatus current = driver.approvalStatus();
                if (DriverApprovalRules.afterProfileSave(current, true) != current) {
                    drivers.applyApproval(founderUid, ApprovalStatus.APPROVED, null, founderUid);
                    log.info("SuperUserBootstrapDebug | uid={} estaba '{}' como conductor y quedó aprobado", founderUid, current.wireValue());
                }
            });
        } catch (RuntimeException e) {
            log.error("SuperUserBootstrapDebug | Error al aprobar el perfil de conductor del fundador uid={}: {}", founderUid, e.getMessage(), e);
        }
    }
}
