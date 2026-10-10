package com.areascript.taxiapp.service;

import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.common.error.ConflictException;
import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.error.ForbiddenException;
import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.dto.DriverDTO;
import com.areascript.taxiapp.dto.DriverPageDTO;
import com.areascript.taxiapp.dto.VehicleDTO;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Service
public class DriverAdminService {

    private static final Logger log = LoggerFactory.getLogger(DriverAdminService.class);
    private static final String DRIVERS_COLLECTION = "drivers";
    private static final String VEHICLES_COLLECTION = "vehicles";
    private static final Set<String> VALID_ROLES = Set.of("superuser", "admin", "driver");
    private static final Set<String> VALID_APPROVAL_STATUSES = Set.of("pending", "approved", "rejected");

    private final Firestore firestore;
    private final FirebaseAuth firebaseAuth;
    private final AccountCleanupService accountCleanupService;
    private final TaxiappProperties.Admin adminSettings;

    public DriverAdminService(
            Firestore firestore,
            FirebaseAuth firebaseAuth,
            AccountCleanupService accountCleanupService,
            TaxiappProperties properties
    ) {
        this.firestore = firestore;
        this.firebaseAuth = firebaseAuth;
        this.accountCleanupService = accountCleanupService;
        this.adminSettings = properties.admin();
    }

    public void deleteDriver(String uid, boolean callerIsSuperUser) {
        UserRecord targetUser;
        try {
            targetUser = firebaseAuth.getUser(uid);
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                throw driverNotFound(uid);
            }
            log.error("DriverAdminDebug | Error al consultar uid={} antes de eliminar: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo verificar el usuario antes de eliminarlo", e);
        }

        Map<String, Object> targetClaims = targetUser.getCustomClaims();
        boolean targetIsSuperUser = targetClaims != null && Boolean.TRUE.equals(targetClaims.get("superuser"));
        boolean targetIsAdmin = targetClaims != null && Boolean.TRUE.equals(targetClaims.get("admin"));

        if (targetIsSuperUser) {
            throw new ForbiddenException("No se puede eliminar a un superuser");
        }
        if (targetIsAdmin && !callerIsSuperUser) {
            throw new ForbiddenException("Un admin no puede eliminar a otro admin");
        }

        try {
            firebaseAuth.deleteUser(uid);
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                throw driverNotFound(uid);
            }
            log.error("DriverAdminDebug | Error al eliminar uid={} de Firebase Auth: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo eliminar el usuario de Firebase Authentication", e);
        }

        // Limpieza completa (doc de drivers, vehículo asociado, foto en
        // Storage y chats) -- antes de esto solo se borraba el doc de
        // Firestore, dejando vehículo/foto/chats huérfanos. Compartido con la
        // autoeliminación de cuenta (ver AccountService.deleteOwnAccount).
        accountCleanupService.wipeUserData(uid);

        log.info("DriverAdminDebug | Driver uid={} eliminado correctamente de Firebase Auth y limpiado por completo", uid);
    }

    // Fetch completo, sin paginar -- usado por el buscador del panel admin
    // (necesita ver TODOS los conductores para filtrar por nombre/correo/
    // teléfono), no para la navegación normal de la lista.
    public DriverPageDTO listAllDrivers() {
        try {
            List<QueryDocumentSnapshot> documents = firestore.collection(DRIVERS_COLLECTION).get().get().getDocuments();
            List<DriverDTO> drivers = new ArrayList<>();
            for (QueryDocumentSnapshot document : documents) {
                drivers.add(toDriverDTO(document));
            }
            return new DriverPageDTO(drivers, null, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido al listar la colección '{}': {}", DRIVERS_COLLECTION, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo obtener la lista de drivers", e);
        } catch (ExecutionException e) {
            log.error("DriverAdminDebug | Error al listar la colección '{}': {}", DRIVERS_COLLECTION, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo obtener la lista de drivers", e);
        }
    }

    // Paginación real por cursor (Firestore no soporta offset eficiente):
    // se ordena por el id del documento, que siempre existe en todo
    // documento -- a diferencia de, por ejemplo, 'createdAt', que algún
    // documento viejo podría no tener, y Firestore excluye silenciosamente
    // de un orderBy a los documentos que no tienen el campo indicado.
    // pageSize+1 se pide de más para saber si hay una página siguiente sin
    // necesidad de un segundo viaje al servidor.
    public DriverPageDTO listDriversPage(int pageSize, String cursor) {
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        try {
            Query query = firestore.collection(DRIVERS_COLLECTION)
                    .orderBy(FieldPath.documentId())
                    .limit(safePageSize + 1);
            if (cursor != null && !cursor.isBlank()) {
                query = query.startAfter(cursor);
            }

            List<QueryDocumentSnapshot> documents = query.get().get().getDocuments();
            boolean hasMore = documents.size() > safePageSize;
            List<QueryDocumentSnapshot> pageDocuments = hasMore
                    ? documents.subList(0, safePageSize)
                    : documents;

            List<DriverDTO> drivers = new ArrayList<>();
            for (QueryDocumentSnapshot document : pageDocuments) {
                drivers.add(toDriverDTO(document));
            }

            String nextCursor = (hasMore && !pageDocuments.isEmpty())
                    ? pageDocuments.get(pageDocuments.size() - 1).getId()
                    : null;

            return new DriverPageDTO(drivers, nextCursor, hasMore);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido al paginar la colección '{}': {}", DRIVERS_COLLECTION, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo obtener la página de drivers", e);
        } catch (ExecutionException e) {
            log.error("DriverAdminDebug | Error al paginar la colección '{}': {}", DRIVERS_COLLECTION, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo obtener la página de drivers", e);
        }
    }

    // Refresco puntual de un solo conductor -- usado por la app para
    // resincronizar su estado al volver de la pantalla de detalle, sin
    // tener que recargar (y perder la posición de) la lista paginada entera.
    public DriverDTO getDriver(String uid) {
        try {
            DocumentSnapshot snapshot = firestore.collection(DRIVERS_COLLECTION).document(uid).get().get();
            if (!snapshot.exists()) {
                throw driverNotFound(uid);
            }
            return toDriverDTO(snapshot);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido al obtener uid={}: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo obtener el conductor", e);
        } catch (ExecutionException e) {
            log.error("DriverAdminDebug | Error al obtener uid={}: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo obtener el conductor", e);
        }
    }

    private DriverDTO toDriverDTO(DocumentSnapshot document) throws InterruptedException, ExecutionException {
        Map<String, Object> data = document.getData();
        String vehicleId = stringField(data, "vehicleId");
        VehicleDTO vehicle = vehicleId.isBlank() ? null : fetchVehicle(vehicleId);

        return new DriverDTO(
                document.getId(),
                stringField(data, "firstName"),
                stringField(data, "lastName"),
                stringField(data, "email"),
                stringField(data, "phoneNumber"),
                data.get("photoUrl") == null ? null : data.get("photoUrl").toString(),
                stringField(data, "fcmToken"),
                numberField(data, "rating", 5.0),
                data.getOrDefault("role", "driver").toString(),
                data.getOrDefault("approvalStatus", "approved").toString(),
                Boolean.TRUE.equals(data.get("isBlocked")),
                data.get("blockReason") == null ? null : data.get("blockReason").toString(),
                timestampField(data, "blockedAt"),
                data.get("blockedBy") == null ? null : data.get("blockedBy").toString(),
                data.get("rejectionReason") == null ? null : data.get("rejectionReason").toString(),
                timestampField(data, "createdAt"),
                timestampField(data, "updatedAt"),
                vehicle
        );
    }

    private VehicleDTO fetchVehicle(String vehicleId) throws InterruptedException, ExecutionException {
        DocumentSnapshot snapshot = firestore.collection(VEHICLES_COLLECTION).document(vehicleId).get().get();
        if (!snapshot.exists() || snapshot.getData() == null) {
            return null;
        }

        Map<String, Object> data = snapshot.getData();
        return new VehicleDTO(
                snapshot.getId(),
                stringField(data, "driverId"),
                stringField(data, "plate"),
                stringField(data, "brand"),
                stringField(data, "model"),
                (int) numberField(data, "year", 0),
                stringField(data, "color"),
                stringField(data, "registrationNumber"),
                data.getOrDefault("verificationStatus", "pending").toString(),
                timestampField(data, "createdAt"),
                timestampField(data, "updatedAt")
        );
    }

    private static NotFoundException driverNotFound(String uid) {
        return new NotFoundException("No existe ningún driver con uid: " + uid);
    }

    private static String stringField(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString();
    }

    private static double numberField(Map<String, Object> data, String key, double defaultValue) {
        Object value = data.get(key);
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    private static String timestampField(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof Timestamp timestamp ? timestamp.toDate().toInstant().toString() : null;
    }

    // Jerarquía compartida por todas las acciones de moderación (cambiar rol,
    // aprobar/rechazar, bloquear/desbloquear): nadie -- ni otro superuser --
    // puede actuar sobre un superuser, y un admin normal no puede actuar
    // sobre otro admin (solo un superuser puede). callerIsSuperUser=true
    // hace que la segunda regla nunca aplique (ver updateDriverRole, donde
    // el caller siempre es superuser por la jerarquía del controller).
    private void assertCanModerate(String targetUid, boolean callerIsSuperUser) {
        UserRecord targetUser;
        try {
            targetUser = firebaseAuth.getUser(targetUid);
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                throw driverNotFound(targetUid);
            }
            log.error(
                    "DriverAdminDebug | Error al consultar uid={} antes de verificar jerarquía de roles: {}",
                    targetUid, e.getMessage(), e
            );
            throw new ExternalServiceException("No se pudo verificar el usuario antes de aplicar la acción", e);
        }

        Map<String, Object> targetClaims = targetUser.getCustomClaims();
        boolean targetIsSuperUser = targetClaims != null && Boolean.TRUE.equals(targetClaims.get("superuser"));
        boolean targetIsAdmin = targetClaims != null && Boolean.TRUE.equals(targetClaims.get("admin"));

        log.info(
                "DriverAdminDebug | assertCanModerate uid={} -> claims={}, targetIsSuperUser={}, targetIsAdmin={}, callerIsSuperUser={}",
                targetUid, targetClaims, targetIsSuperUser, targetIsAdmin, callerIsSuperUser
        );

        if (targetIsSuperUser) {
            throw new ForbiddenException("No se puede realizar esta acción sobre un superuser");
        }
        if (targetIsAdmin && !callerIsSuperUser) {
            throw new ForbiddenException("Un admin no puede realizar esta acción sobre otro admin");
        }
    }

    public void updateDriverRole(String uid, String role) {
        if (!VALID_ROLES.contains(role)) {
            throw new InvalidInputException("Rol inválido: '" + role + "'. Valores permitidos: admin, driver");
        }
        // "superuser" es un rol válido del sistema, pero no un destino
        // permitido desde este endpoint -- nadie, ni siquiera otro
        // superuser, puede promover a alguien a superuser acá (eso solo
        // ocurre vía SuperUserBootstrap / intervención manual).
        if ("superuser".equals(role)) {
            throw new ForbiddenException("No se puede promover a nadie a superuser");
        }
        if (adminSettings.isFounder(uid)) {
            throw new ForbiddenException("No se puede cambiar el rol del super administrador fundador");
        }
        // Este endpoint ya exige caller superuser a nivel de controller, así
        // que acá solo hace falta cerrar el caso "superuser contra otro
        // superuser" -- la rama admin-contra-admin de assertCanModerate
        // nunca se dispara con callerIsSuperUser=true.
        assertCanModerate(uid, true);

        boolean isSuperUser = "superuser".equals(role);
        boolean isAdmin = isSuperUser || "admin".equals(role);
        try {
            firebaseAuth.setCustomUserClaims(uid, Map.of("admin", isAdmin, "superuser", isSuperUser));
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                throw driverNotFound(uid);
            }
            log.error("DriverAdminDebug | Error al asignar el custom claim admin={} a uid={}: {}", isAdmin, uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo actualizar el custom claim en Firebase Authentication", e);
        }

        try {
            firestore.collection(DRIVERS_COLLECTION).document(uid).update("role", role).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido al actualizar el campo 'role' de uid={}: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("El custom claim se actualizó pero falló al actualizar el campo 'role' en Firestore", e);
        } catch (ExecutionException e) {
            log.error("DriverAdminDebug | Error al actualizar el campo 'role' de uid={}: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("El custom claim se actualizó pero falló al actualizar el campo 'role' en Firestore", e);
        }

        log.info("DriverAdminDebug | Rol actualizado correctamente: uid={} -> role={}", uid, role);
    }

    public void updateApprovalStatus(String uid, String status, String reason, String actorUid, boolean callerIsSuperUser) {
        if (!VALID_APPROVAL_STATUSES.contains(status)) {
            throw new InvalidInputException("Estado de aprobación inválido: '" + status
                    + "'. Valores permitidos: pending, approved, rejected");
        }
        if ("rejected".equals(status) && (reason == null || reason.isBlank())) {
            throw new InvalidInputException("Se requiere un motivo para rechazar al conductor");
        }

        DocumentSnapshot snapshot;
        try {
            snapshot = firestore.collection(DRIVERS_COLLECTION).document(uid).get().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido al verificar uid={} antes de actualizar approvalStatus: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo verificar el conductor", e);
        } catch (ExecutionException e) {
            log.error("DriverAdminDebug | Error al verificar uid={} antes de actualizar approvalStatus: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo verificar el conductor", e);
        }
        if (!snapshot.exists()) {
            throw driverNotFound(uid);
        }

        // Un admin no puede aprobar/rechazar a otro admin ni a un superuser --
        // antes de este chequeo, cualquier admin podía llamar este método
        // sobre cualquier uid sin restricción de jerarquía.
        assertCanModerate(uid, callerIsSuperUser);

        Map<String, Object> updates = new HashMap<>();
        updates.put("approvalStatus", status);
        if ("approved".equals(status)) {
            updates.put("approvedAt", Timestamp.now());
            updates.put("approvedBy", actorUid);
            updates.put("rejectionReason", FieldValue.delete());
        } else if ("rejected".equals(status)) {
            updates.put("rejectionReason", reason);
            updates.put("rejectedAt", Timestamp.now());
            updates.put("rejectedBy", actorUid);
        }

        try {
            firestore.collection(DRIVERS_COLLECTION).document(uid).update(updates).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido al actualizar approvalStatus de uid={}: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo actualizar el estado de aprobación en Firestore", e);
        } catch (ExecutionException e) {
            log.error("DriverAdminDebug | Error al actualizar approvalStatus de uid={}: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo actualizar el estado de aprobación en Firestore", e);
        }

        log.info("DriverAdminDebug | approvalStatus actualizado correctamente: uid={} -> status={}", uid, status);
    }

    // callerIsSuperUser aplica la misma jerarquía que deleteDriver: nadie
    // bloquea a un superuser, y un admin normal no puede bloquear a otro admin.
    public void updateBlockStatus(String uid, boolean blocked, String reason, String actorUid, boolean callerIsSuperUser) {
        if (blocked && (reason == null || reason.isBlank())) {
            throw new InvalidInputException("Se requiere un motivo para bloquear al conductor");
        }

        DocumentSnapshot snapshot;
        try {
            snapshot = firestore.collection(DRIVERS_COLLECTION).document(uid).get().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido al verificar uid={} antes de actualizar isBlocked: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo verificar el conductor", e);
        } catch (ExecutionException e) {
            log.error("DriverAdminDebug | Error al verificar uid={} antes de actualizar isBlocked: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo verificar el conductor", e);
        }
        if (!snapshot.exists()) {
            throw driverNotFound(uid);
        }

        Map<String, Object> data = snapshot.getData();
        String approvalStatus = data == null ? "approved" : data.getOrDefault("approvalStatus", "approved").toString();
        if (blocked && !"approved".equals(approvalStatus)) {
            throw new ConflictException("El conductor " + uid + " no está aprobado, no se puede bloquear");
        }

        // Se aplica tanto al bloquear como al desbloquear -- por defensa en
        // profundidad, no solo al bloquear: un admin tampoco debería poder
        // desbloquear a otro admin.
        assertCanModerate(uid, callerIsSuperUser);

        Map<String, Object> updates = new HashMap<>();
        updates.put("isBlocked", blocked);
        if (blocked) {
            updates.put("blockReason", reason);
            updates.put("blockedAt", Timestamp.now());
            updates.put("blockedBy", actorUid);
        } else {
            updates.put("blockReason", FieldValue.delete());
            updates.put("blockedAt", FieldValue.delete());
            updates.put("blockedBy", FieldValue.delete());
        }

        try {
            firestore.collection(DRIVERS_COLLECTION).document(uid).update(updates).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido al actualizar isBlocked de uid={}: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo actualizar el estado de bloqueo en Firestore", e);
        } catch (ExecutionException e) {
            log.error("DriverAdminDebug | Error al actualizar isBlocked de uid={}: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo actualizar el estado de bloqueo en Firestore", e);
        }

        log.info("DriverAdminDebug | isBlocked actualizado correctamente: uid={} -> blocked={}", uid, blocked);
    }
}
