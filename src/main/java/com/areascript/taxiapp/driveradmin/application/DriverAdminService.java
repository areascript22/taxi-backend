package com.areascript.taxiapp.driveradmin.application;

import com.areascript.taxiapp.account.domain.UserDataCleaner;
import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.common.error.ConflictException;
import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.error.ForbiddenException;
import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.Driver;
import com.areascript.taxiapp.driveradmin.domain.DriverPage;
import com.areascript.taxiapp.driveradmin.domain.DriverRepository;
import com.areascript.taxiapp.driveradmin.domain.DriverRole;
import com.areascript.taxiapp.driveradmin.domain.ModerationPolicy;
import com.areascript.taxiapp.identity.domain.AccountRoles;
import com.areascript.taxiapp.identity.domain.IdentityProvider;
import com.areascript.taxiapp.user.domain.UserAccounts;
import com.areascript.taxiapp.user.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Casos de uso del panel de administración de conductores. Aplica las reglas
// (jerarquía de roles, motivos obligatorios, quién puede bloquearse) y
// coordina la cuenta de autenticación con los datos del conductor; no conoce
// Firebase ni Firestore.
@Service
public class DriverAdminService {

    private static final Logger log = LoggerFactory.getLogger(DriverAdminService.class);

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final DriverRepository drivers;
    private final IdentityProvider identityProvider;
    private final UserDataCleaner dataCleaner;
    private final UserAccounts userAccounts;
    private final TaxiappProperties.Admin adminSettings;

    public DriverAdminService(
            DriverRepository drivers,
            IdentityProvider identityProvider,
            UserDataCleaner dataCleaner,
            UserAccounts userAccounts,
            TaxiappProperties properties
    ) {
        this.drivers = drivers;
        this.identityProvider = identityProvider;
        this.dataCleaner = dataCleaner;
        this.userAccounts = userAccounts;
        this.adminSettings = properties.admin();
    }

    // Borra primero la cuenta de autenticación y después los datos (a
    // diferencia de AccountService.deleteOwnAccount, que lo hace al revés):
    // acá el admin puede reintentar sin depender del token de un tercero.
    public void deleteDriver(String uid, boolean callerIsSuperUser) {
        ModerationPolicy.assertCanModerate(rolesOf(uid), callerIsSuperUser);

        identityProvider.delete(uid);

        // Limpieza completa (doc del conductor, vehículo asociado, foto en
        // Storage y chats). Compartida con la autoeliminación de cuenta.
        dataCleaner.wipe(uid);
        // La persona y, en cascada, sus perfiles y vehículos en Postgres.
        userAccounts.remove(uid);

        log.info("DriverAdminDebug | Driver uid={} eliminado correctamente de Firebase Auth y limpiado por completo", uid);
    }

    // Fetch completo, sin paginar -- usado por el buscador del panel admin
    // (necesita ver TODOS los conductores para filtrar por nombre/correo/
    // teléfono), no para la navegación normal de la lista.
    public DriverPage listAllDrivers() {
        return drivers.findAll();
    }

    public DriverPage listDriversPage(int pageSize, String cursor) {
        int safePageSize = Math.max(MIN_PAGE_SIZE, Math.min(pageSize, MAX_PAGE_SIZE));
        return drivers.findPage(safePageSize, cursor);
    }

    // Refresco puntual de un solo conductor -- usado por la app para
    // resincronizar su estado al volver de la pantalla de detalle, sin
    // tener que recargar (y perder la posición de) la lista paginada entera.
    public Driver getDriver(String uid) {
        return requireDriver(uid);
    }

    public void updateDriverRole(String uid, String role) {
        DriverRole newRole = DriverRole.parse(role)
                .orElseThrow(() -> new InvalidInputException(
                        "Rol inválido: '" + role + "'. Valores permitidos: admin, driver"));

        // "superuser" es un rol válido del sistema, pero no un destino
        // permitido desde este endpoint -- nadie, ni siquiera otro
        // superuser, puede promover a alguien a superuser acá (eso solo
        // ocurre vía el arranque del fundador / intervención manual).
        if (newRole == DriverRole.SUPERUSER) {
            throw new ForbiddenException("No se puede promover a nadie a superuser");
        }
        if (adminSettings.isFounder(uid)) {
            throw new ForbiddenException("No se puede cambiar el rol del super administrador fundador");
        }
        // Este endpoint ya exige caller superuser (o fundador) a nivel de
        // controller, así que acá solo hace falta cerrar el caso "superuser
        // contra otro superuser" -- la rama admin-contra-admin de la política
        // nunca se dispara con callerIsSuperUser=true.
        ModerationPolicy.assertCanModerate(rolesOf(uid), true);

        drivers.updateRole(uid, newRole);

        // El rol que manda en cada request es el de la base (users.role_code).
        // Idempotente: si falla, se repite la operación.
        try {
            userAccounts.setRole(uid, newRole == DriverRole.ADMIN ? UserRole.ADMIN : UserRole.USER);
        } catch (ExternalServiceException e) {
            log.error("DriverAdminDebug | No se pudo guardar el rol de uid={} en la base de usuarios", uid);
            throw e;
        }

        log.info("DriverAdminDebug | Rol actualizado correctamente: uid={} -> role={}", uid, newRole.wireValue());
    }

    public void updateApprovalStatus(String uid, String status, String reason, String actorUid, boolean callerIsSuperUser) {
        ApprovalStatus newStatus = ApprovalStatus.parse(status)
                .orElseThrow(() -> new InvalidInputException(
                        "Estado de aprobación inválido: '" + status + "'. Valores permitidos: pending, approved, rejected"));
        if (newStatus == ApprovalStatus.REJECTED && (reason == null || reason.isBlank())) {
            throw new InvalidInputException("Se requiere un motivo para rechazar al conductor");
        }

        requireDriver(uid);

        // Un admin no puede aprobar/rechazar a otro admin ni a un superuser.
        ModerationPolicy.assertCanModerate(rolesOf(uid), callerIsSuperUser);

        drivers.applyApproval(uid, newStatus, reason, actorUid);
        log.info("DriverAdminDebug | approvalStatus actualizado correctamente: uid={} -> status={}", uid, newStatus.wireValue());
    }

    // callerIsSuperUser aplica la misma jerarquía que deleteDriver: nadie
    // bloquea a un superuser, y un admin normal no puede bloquear a otro admin.
    public void updateBlockStatus(String uid, boolean blocked, String reason, String actorUid, boolean callerIsSuperUser) {
        if (blocked && (reason == null || reason.isBlank())) {
            throw new InvalidInputException("Se requiere un motivo para bloquear al conductor");
        }

        Driver driver = requireDriver(uid);
        if (blocked && driver.approvalStatus() != ApprovalStatus.APPROVED) {
            throw new ConflictException("El conductor " + uid + " no está aprobado, no se puede bloquear");
        }

        // Se aplica tanto al bloquear como al desbloquear -- por defensa en
        // profundidad, no solo al bloquear: un admin tampoco debería poder
        // desbloquear a otro admin.
        ModerationPolicy.assertCanModerate(rolesOf(uid), callerIsSuperUser);

        drivers.applyBlock(uid, blocked, reason, actorUid);
        log.info("DriverAdminDebug | isBlocked actualizado correctamente: uid={} -> blocked={}", uid, blocked);
    }

    private Driver requireDriver(String uid) {
        return drivers.findById(uid).orElseThrow(() -> driverNotFound(uid));
    }

    private AccountRoles rolesOf(String uid) {
        UserRole role = userAccounts.find(uid).orElseThrow(() -> driverNotFound(uid)).role();
        AccountRoles roles = new AccountRoles(role.isAdmin(), role.isSuperUser());
        log.info("DriverAdminDebug | roles de uid={}: admin={}, superUser={}", uid, roles.admin(), roles.superUser());
        return roles;
    }

    private static NotFoundException driverNotFound(String uid) {
        return new NotFoundException("No existe ningún driver con uid: " + uid);
    }
}
