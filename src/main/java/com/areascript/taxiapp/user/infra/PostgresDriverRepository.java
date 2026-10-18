package com.areascript.taxiapp.user.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.Driver;
import com.areascript.taxiapp.driveradmin.domain.DriverPage;
import com.areascript.taxiapp.driveradmin.domain.DriverRepository;
import com.areascript.taxiapp.driveradmin.domain.DriverRole;
import com.areascript.taxiapp.driveradmin.domain.Vehicle;
import com.areascript.taxiapp.user.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Los conductores y sus vehículos en Postgres: la fuente de verdad (Firestore
// ya no guarda perfiles). Implementa el puerto del panel de administración.
//
// Una persona es "conductor" si tiene fila en `drivers`. Sus datos se reparten:
// nombre, correo, rol y bloqueo en `users`; teléfono, foto, calificación y
// aprobación en `drivers`; el auto en `vehicles`.
//
// El rol del dominio del panel (DriverRole) conserva su vocabulario ("driver",
// "admin", "superuser"); en la base "driver" no es un rol sino la ausencia de
// privilegios (users.role_code = 'user').
@Component
@ConditionalOnProperty(name = "taxiapp.persistence.postgres", havingValue = "true", matchIfMissing = true)
public class PostgresDriverRepository implements DriverRepository {

    private static final Logger log = LoggerFactory.getLogger(PostgresDriverRepository.class);

    private final UserRowRepository users;
    private final DriverRowRepository drivers;
    private final VehicleRowRepository vehicles;

    public PostgresDriverRepository(
            UserRowRepository users,
            DriverRowRepository drivers,
            VehicleRowRepository vehicles) {
        this.users = users;
        this.drivers = drivers;
        this.vehicles = vehicles;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Driver> findById(String uid) {
        try {
            Optional<UserRow> user = users.findByFirebaseUid(uid);
            if (user.isEmpty()) {
                return Optional.empty();
            }
            List<Driver> found = toDrivers(List.of(user.get()));
            return found.stream().findFirst();
        } catch (DataAccessException e) {
            throw failure("leer al conductor " + uid, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DriverPage findAll() {
        try {
            return new DriverPage(toDrivers(users.findAllDriverUsers()), null, false);
        } catch (DataAccessException e) {
            throw failure("listar a los conductores", e);
        }
    }

    // Paginación real por cursor: pageSize+1 filas para saber si hay otra página
    // sin un segundo viaje a la base.
    @Override
    @Transactional(readOnly = true)
    public DriverPage findPage(int pageSize, String cursor) {
        try {
            String after = cursor == null ? "" : cursor;
            List<UserRow> rows = users.findDriverUsersAfter(after, PageRequest.of(0, pageSize + 1));
            boolean hasMore = rows.size() > pageSize;
            List<UserRow> page = hasMore ? rows.subList(0, pageSize) : rows;

            String nextCursor = hasMore && !page.isEmpty()
                    ? page.get(page.size() - 1).getFirebaseUid()
                    : null;
            return new DriverPage(toDrivers(page), nextCursor, hasMore);
        } catch (DataAccessException e) {
            throw failure("listar una página de conductores", e);
        }
    }

    @Override
    @Transactional
    public void updateRole(String uid, DriverRole role) {
        try {
            int updated = users.updateRole(uid, toRoleCode(role));
            if (updated == 0) {
                throw new NotFoundException("No existe ningún driver con uid: " + uid);
            }
        } catch (DataAccessException e) {
            throw failure("cambiar el rol de " + uid, e);
        }
    }

    // Como updateRole pero crea a la persona si no existe (el fundador puede no
    // haber iniciado sesión todavía en este entorno).
    @Override
    @Transactional
    public void ensureRole(String uid, DriverRole role) {
        try {
            users.insertIfAbsent(UUID.randomUUID(), uid, "", "", "");
            users.updateRole(uid, toRoleCode(role));
        } catch (DataAccessException e) {
            throw failure("sincronizar el rol de " + uid, e);
        }
    }

    @Override
    @Transactional
    public void applyApproval(String uid, ApprovalStatus status, String reason, String actorUid) {
        try {
            DriverRow driver = driverOf(uid);
            UUID actor = actorId(actorUid);
            Instant now = Instant.now();

            driver.setApprovalStatus(status.wireValue());
            if (status == ApprovalStatus.APPROVED) {
                driver.setApprovedAt(now);
                driver.setApprovedBy(actor);
                // Aprobar limpia el motivo de un rechazo anterior.
                driver.setRejectionReason(null);
            } else if (status == ApprovalStatus.REJECTED) {
                driver.setRejectionReason(reason);
                driver.setRejectedAt(now);
                driver.setRejectedBy(actor);
            }
            driver.setUpdatedAt(now);
            drivers.saveAndFlush(driver);
        } catch (DataAccessException e) {
            throw failure("cambiar la aprobación de " + uid, e);
        }
    }

    // El bloqueo es de la CUENTA (users), no del perfil de conductor.
    @Override
    @Transactional
    public void applyBlock(String uid, boolean blocked, String reason, String actorUid) {
        try {
            UserRow user = users.findByFirebaseUid(uid)
                    .orElseThrow(() -> new NotFoundException("No existe ningún driver con uid: " + uid));
            Instant now = Instant.now();

            user.setBlocked(blocked);
            if (blocked) {
                user.setBlockReason(reason);
                user.setBlockedAt(now);
                user.setBlockedBy(actorId(actorUid));
            } else {
                // Desbloquear no deja rastro del bloqueo anterior.
                user.setBlockReason(null);
                user.setBlockedAt(null);
                user.setBlockedBy(null);
            }
            user.setUpdatedAt(now);
            users.saveAndFlush(user);
        } catch (DataAccessException e) {
            throw failure("cambiar el bloqueo de " + uid, e);
        }
    }

    // ---- Mapeo filas -> modelo ----

    // Convierte varias personas de una vez, con una consulta por tabla (no una
    // por conductor): el panel lista decenas de conductores.
    private List<Driver> toDrivers(List<UserRow> userRows) {
        if (userRows.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = userRows.stream().map(UserRow::getId).toList();
        Map<UUID, DriverRow> driverById = new HashMap<>();
        drivers.findAllById(ids).forEach(row -> driverById.put(row.getUserId(), row));

        Set<String> vehicleIds = new HashSet<>();
        driverById.values().forEach(row -> {
            if (row.getVehicleId() != null) {
                vehicleIds.add(row.getVehicleId());
            }
        });
        Map<String, VehicleRow> vehicleById = new HashMap<>();
        vehicles.findAllById(vehicleIds).forEach(row -> vehicleById.put(row.getVehicleId(), row));

        // Quién bloqueó: users.id -> uid de Firebase (lo que ve el panel).
        Set<UUID> actorIds = new HashSet<>();
        userRows.forEach(row -> {
            if (row.getBlockedBy() != null) {
                actorIds.add(row.getBlockedBy());
            }
        });
        Map<UUID, String> actorUidById = new HashMap<>();
        users.findAllById(actorIds).forEach(row -> actorUidById.put(row.getId(), row.getFirebaseUid()));

        List<Driver> result = new ArrayList<>();
        for (UserRow user : userRows) {
            DriverRow driver = driverById.get(user.getId());
            if (driver == null) {
                continue;
            }
            result.add(toDriver(user, driver, vehicleById.get(driver.getVehicleId()), actorUidById));
        }
        return result;
    }

    private static Driver toDriver(
            UserRow user,
            DriverRow driver,
            VehicleRow vehicle,
            Map<UUID, String> actorUidById) {
        return new Driver(
                user.getFirebaseUid(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                driver.getPhoneNumber(),
                driver.getPhotoUrl(),
                driver.getFcmToken() == null ? "" : driver.getFcmToken(),
                driver.getRating(),
                toDriverRole(UserRole.fromStored(user.getRoleCode())),
                ApprovalStatus.fromStored(driver.getApprovalStatus()),
                user.isBlocked(),
                user.getBlockReason(),
                user.getBlockedAt(),
                user.getBlockedBy() == null ? null : actorUidById.get(user.getBlockedBy()),
                driver.getRejectionReason(),
                driver.getCreatedAt(),
                driver.getUpdatedAt(),
                vehicle == null ? null : toVehicle(user.getFirebaseUid(), vehicle));
    }

    private static Vehicle toVehicle(String driverUid, VehicleRow row) {
        return new Vehicle(
                row.getVehicleId(),
                driverUid,
                row.getPlate(),
                row.getBrand(),
                row.getModel(),
                row.getYear(),
                row.getColor(),
                row.getRegistrationNumber(),
                row.getVerificationStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private DriverRow driverOf(String uid) {
        UserRow user = users.findByFirebaseUid(uid)
                .orElseThrow(() -> new NotFoundException("No existe ningún driver con uid: " + uid));
        return drivers.findById(user.getId())
                .orElseThrow(() -> new NotFoundException("No existe ningún driver con uid: " + uid));
    }

    // De uid de Firebase del que actuó a su users.id; null si ya no existe.
    private UUID actorId(String actorUid) {
        if (actorUid == null || actorUid.isBlank()) {
            return null;
        }
        return users.findByFirebaseUid(actorUid).map(UserRow::getId).orElse(null);
    }

    // ---- Vocabulario ----

    private static DriverRole toDriverRole(UserRole role) {
        return switch (role) {
            case USER -> DriverRole.DRIVER;
            case ADMIN -> DriverRole.ADMIN;
            case SUPERUSER -> DriverRole.SUPERUSER;
        };
    }

    private static String toRoleCode(DriverRole role) {
        return switch (role) {
            case DRIVER -> UserRole.USER.code();
            case ADMIN -> UserRole.ADMIN.code();
            case SUPERUSER -> UserRole.SUPERUSER.code();
        };
    }

    private static ExternalServiceException failure(String action, DataAccessException e) {
        log.error("DriverAdminDebug | No se pudo {}: {}", action, e.getMessage(), e);
        return new ExternalServiceException("No se pudo " + action, e);
    }
}
