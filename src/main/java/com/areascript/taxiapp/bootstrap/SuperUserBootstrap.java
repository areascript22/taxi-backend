package com.areascript.taxiapp.bootstrap;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

// Se ejecuta en cada arranque del backend y garantiza que la cuenta dueña
// del proyecto tenga el custom claim 'superuser' (y admin=true) en Firebase
// Authentication, y el campo role='superuser' en su documento de Firestore.
// Es idempotente: si el claim ya está asignado, no lo vuelve a setear, pero
// siempre re-sincroniza el campo 'role' en Firestore por si una corrida
// anterior falló a mitad de camino. Si el uid no
// existe en el proyecto de Firebase activo (ej. al correr con el profile
// 'prod', que usa un proyecto de Firebase distinto al de dev), lo registra
// como advertencia y deja que el arranque continúe con normalidad.
@Component
public class SuperUserBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperUserBootstrap.class);
    private static final String DRIVERS_COLLECTION = "drivers";
    private static final String SUPER_USER_UID = "AhsQJcGg49UQkLWiuy6trG0BWzq1";

    private final FirebaseAuth firebaseAuth;
    private final Firestore firestore;

    public SuperUserBootstrap(FirebaseAuth firebaseAuth, Firestore firestore) {
        this.firebaseAuth = firebaseAuth;
        this.firestore = firestore;
    }

    @Override
    public void run(String... args) {
        UserRecord user;
        try {
            user = firebaseAuth.getUser(SUPER_USER_UID);
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                log.warn("SuperUserBootstrapDebug | uid={} no existe en este proyecto de Firebase, se omite el bootstrap de superuser", SUPER_USER_UID);
            } else {
                log.error("SuperUserBootstrapDebug | Error al consultar uid={}: {}", SUPER_USER_UID, e.getMessage(), e);
            }
            return;
        } catch (Exception e) {
            log.error("SuperUserBootstrapDebug | Error inesperado al consultar uid={}: {}", SUPER_USER_UID, e.getMessage(), e);
            return;
        }

        if (!Boolean.TRUE.equals(user.getCustomClaims().get("superuser"))) {
            try {
                firebaseAuth.setCustomUserClaims(SUPER_USER_UID, Map.of("admin", true, "superuser", true));
                log.info("SuperUserBootstrapDebug | uid={} promovido a superuser correctamente", SUPER_USER_UID);
            } catch (Exception e) {
                log.error("SuperUserBootstrapDebug | Error al promover a superuser uid={}: {}", SUPER_USER_UID, e.getMessage(), e);
                return;
            }
        }

        // Siempre se sincroniza, incluso si el claim ya estaba asignado en una
        // corrida anterior: si esa corrida falló después de setear el claim
        // pero antes de escribir en Firestore, este merge es el que corrige
        // el campo 'role' sin depender de reintentar el bloque de arriba.
        try {
            firestore.collection(DRIVERS_COLLECTION)
                    .document(SUPER_USER_UID)
                    .set(Map.of("role", "superuser"), SetOptions.merge())
                    .get();
        } catch (Exception e) {
            log.error("SuperUserBootstrapDebug | Error al sincronizar role=superuser en Firestore para uid={}: {}", SUPER_USER_UID, e.getMessage(), e);
        }
    }
}
