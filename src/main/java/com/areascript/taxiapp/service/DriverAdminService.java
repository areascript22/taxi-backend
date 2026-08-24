package com.areascript.taxiapp.service;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
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
    private static final Set<String> VALID_ROLES = Set.of("admin", "driver");

    private final Firestore firestore;
    private final FirebaseAuth firebaseAuth;

    public DriverAdminService(Firestore firestore, FirebaseAuth firebaseAuth) {
        this.firestore = firestore;
        this.firebaseAuth = firebaseAuth;
    }

    public void deleteDriver(String uid) {
        try {
            firebaseAuth.deleteUser(uid);
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                throw new DriverNotFoundException(uid);
            }
            log.error("DriverAdminDebug | Error al eliminar uid={} de Firebase Auth: {}", uid, e.getMessage(), e);
            throw new DriverDeletionException("No se pudo eliminar el usuario de Firebase Authentication", e);
        }

        try {
            firestore.collection(DRIVERS_COLLECTION).document(uid).delete().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido al eliminar el documento uid={} de '{}': {}", uid, DRIVERS_COLLECTION, e.getMessage(), e);
            throw new DriverDeletionException("El usuario se eliminó de Authentication pero falló al eliminar el documento en Firestore", e);
        } catch (ExecutionException e) {
            log.error("DriverAdminDebug | Error al eliminar el documento uid={} de '{}': {}", uid, DRIVERS_COLLECTION, e.getMessage(), e);
            throw new DriverDeletionException("El usuario se eliminó de Authentication pero falló al eliminar el documento en Firestore", e);
        }

        log.info("DriverAdminDebug | Driver uid={} eliminado correctamente de Firebase Auth y de la colección '{}'", uid, DRIVERS_COLLECTION);
    }

    public List<Map<String, Object>> listDrivers() {
        try {
            List<QueryDocumentSnapshot> documents = firestore.collection(DRIVERS_COLLECTION).get().get().getDocuments();
            List<Map<String, Object>> drivers = new ArrayList<>();
            for (QueryDocumentSnapshot document : documents) {
                Map<String, Object> driver = new HashMap<>(document.getData());
                driver.put("uid", document.getId());
                drivers.add(driver);
            }
            return drivers;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido al listar la colección '{}': {}", DRIVERS_COLLECTION, e.getMessage(), e);
            throw new DriverListException("No se pudo obtener la lista de drivers", e);
        } catch (ExecutionException e) {
            log.error("DriverAdminDebug | Error al listar la colección '{}': {}", DRIVERS_COLLECTION, e.getMessage(), e);
            throw new DriverListException("No se pudo obtener la lista de drivers", e);
        }
    }

    public void updateDriverRole(String uid, String role) {
        if (!VALID_ROLES.contains(role)) {
            throw new InvalidRoleException(role);
        }

        boolean isAdmin = "admin".equals(role);
        try {
            firebaseAuth.setCustomUserClaims(uid, Map.of("admin", isAdmin));
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                throw new DriverNotFoundException(uid);
            }
            log.error("DriverAdminDebug | Error al asignar el custom claim admin={} a uid={}: {}", isAdmin, uid, e.getMessage(), e);
            throw new DriverRoleUpdateException("No se pudo actualizar el custom claim en Firebase Authentication", e);
        }

        try {
            firestore.collection(DRIVERS_COLLECTION).document(uid).update("role", role).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("DriverAdminDebug | Interrumpido al actualizar el campo 'role' de uid={}: {}", uid, e.getMessage(), e);
            throw new DriverRoleUpdateException("El custom claim se actualizó pero falló al actualizar el campo 'role' en Firestore", e);
        } catch (ExecutionException e) {
            log.error("DriverAdminDebug | Error al actualizar el campo 'role' de uid={}: {}", uid, e.getMessage(), e);
            throw new DriverRoleUpdateException("El custom claim se actualizó pero falló al actualizar el campo 'role' en Firestore", e);
        }

        log.info("DriverAdminDebug | Rol actualizado correctamente: uid={} -> role={}", uid, role);
    }
}
