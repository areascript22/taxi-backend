package com.areascript.taxiapp.common.firebase;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

// Conexión con Firebase (prefijo "firebase"). Los nombres son los mismos que
// ya se usaban, así que el despliegue no cambia:
//   firebase.credentials.path  <- FIREBASE_CREDENTIALS_PATH (systemd del droplet)
//   firebase.credentials.json  <- FIREBASE_CREDENTIALS_JSON (JSON completo en una
//                                 variable de entorno; tiene prioridad sobre path)
//   firebase.database.url, firebase.storage.bucket <- application-{perfil}.properties
@ConfigurationProperties(prefix = "firebase")
public record FirebaseProperties(
        Credentials credentials,
        Database database,
        Storage storage,
        // Tiempo máximo de espera de cada operación contra Firebase.
        Duration operationTimeout
) {

    public FirebaseProperties {
        credentials = credentials == null ? new Credentials(null, null) : credentials;
        database = database == null ? new Database(null) : database;
        storage = storage == null ? new Storage(null) : storage;
        Objects.requireNonNull(operationTimeout, "Falta firebase.operation-timeout (ver application.properties)");
    }

    public record Credentials(String path, String json) {

        public boolean hasJson() {
            return json != null && !json.isBlank();
        }

        public boolean hasPath() {
            return path != null && !path.isBlank();
        }
    }

    // URL de la Realtime Database (ej. https://<project-id>-default-rtdb.firebaseio.com).
    // Sin ella, Realtime Database no sabe a qué instancia conectarse.
    public record Database(String url) {

        public boolean isConfigured() {
            return url != null && !url.isBlank();
        }
    }

    // Bucket de Storage, con o sin el prefijo "gs://". Opcional: si no está,
    // el borrado de cuenta omite las fotos con un warning.
    public record Storage(String bucket) {

        public boolean isConfigured() {
            return bucket != null && !bucket.isBlank();
        }

        // FirebaseOptions.setStorageBucket espera el nombre sin "gs://", que es
        // como lo muestra Firebase Console.
        public String bucketName() {
            String trimmed = bucket.trim();
            return trimmed.startsWith("gs://") ? trimmed.substring("gs://".length()) : trimmed;
        }
    }
}
