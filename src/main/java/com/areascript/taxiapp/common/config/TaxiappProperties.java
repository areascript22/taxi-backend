package com.areascript.taxiapp.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

// Tiempos, límites y datos de despliegue del servidor (prefijo "taxiapp").
// Los valores viven en application.properties; antes eran constantes privadas
// repartidas por los services.
//
// Si falta un grupo entero, el servidor no arranca y dice cuál: es preferible a
// que un temporizador quede en null y falle recién con la primera carrera.
@ConfigurationProperties(prefix = "taxiapp")
public record TaxiappProperties(Rides rides, Chat chat, Admin admin) {

    public TaxiappProperties {
        Objects.requireNonNull(rides, "Falta la configuración taxiapp.rides.* (ver application.properties)");
        Objects.requireNonNull(chat, "Falta la configuración taxiapp.chat.* (ver application.properties)");
        admin = admin == null ? new Admin(null, null) : admin;
    }

    public record Rides(
            // Cuánto espera el backend a que alguien acepte una solicitud
            // 'pending' antes de cancelarla él mismo. Tiene que ser un poco
            // mayor que el timeout del diálogo "Buscando conductor" de
            // passenger_app (30 s), para que normalmente cancele el cliente.
            Duration pendingRequestExpiry,
            // Margen entre que una carrera llega a un estado terminal y se borra
            // su nodo de Realtime DB: ambas apps tienen que alcanzar a leer el
            // estado final antes de que el nodo desaparezca.
            Duration terminalCleanupDelay
    ) {
        public Rides {
            Objects.requireNonNull(pendingRequestExpiry, "Falta taxiapp.rides.pending-request-expiry");
            Objects.requireNonNull(terminalCleanupDelay, "Falta taxiapp.rides.terminal-cleanup-delay");
        }
    }

    public record Chat(
            // Largo máximo de un mensaje guardado; lo que exceda se corta.
            int maxMessageLength,
            // Largo del texto del mensaje que se muestra en el push.
            int pushPreviewLength,
            // Cuánto vive un mensaje: se guarda como expireAt y lo borra la
            // política TTL de Firestore (ver firebase/README.md).
            Duration retention
    ) {
        public Chat {
            Objects.requireNonNull(retention, "Falta taxiapp.chat.retention");
            if (maxMessageLength <= 0 || pushPreviewLength <= 0) {
                throw new IllegalArgumentException("taxiapp.chat.max-message-length y push-preview-length deben ser > 0");
            }
        }
    }

    // Cuenta dueña del proyecto: SuperUserBootstrap le asegura el claim de
    // superuser en cada arranque, y nadie puede cambiarle el rol.
    //
    // No va en el código ni en archivos versionados: se carga de variables de
    // entorno (TAXIAPP_ADMIN_FOUNDER_UID / TAXIAPP_ADMIN_FOUNDER_EMAIL) o del
    // archivo local secrets/taxiapp-secrets.properties. Es opcional: sin él,
    // el fundador sigue protegido por su claim de superuser (que ya tiene
    // asignado en Firebase), solo que el bootstrap no lo vuelve a verificar.
    public record Admin(String founderUid, String founderEmail) {

        public boolean hasFounder() {
            return founderUid != null && !founderUid.isBlank();
        }

        public boolean isFounder(String uid) {
            return hasFounder() && founderUid.equals(uid);
        }

        // El uid solo no alcanza: también tiene que coincidir el email del
        // token (mismo criterio que tenía DriverAdminController).
        public boolean isFounder(String uid, String email) {
            return isFounder(uid)
                    && founderEmail != null
                    && !founderEmail.isBlank()
                    && founderEmail.equalsIgnoreCase(email);
        }
    }
}
