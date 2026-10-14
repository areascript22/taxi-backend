package com.areascript.taxiapp.common.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    private final FirebaseProperties properties;

    public FirebaseConfig(FirebaseProperties properties) {
        this.properties = properties;
    }

    @Bean
    public FirebaseApp firebaseApp() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }

        GoogleCredentials credentials = loadCredentials(properties.credentials());

        FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder().setCredentials(credentials);
        if (credentials instanceof ServiceAccountCredentials serviceAccountCredentials) {
            optionsBuilder.setProjectId(serviceAccountCredentials.getProjectId());
        }
        if (properties.database().isConfigured()) {
            optionsBuilder.setDatabaseUrl(properties.database().url());
        }
        if (properties.storage().isConfigured()) {
            optionsBuilder.setStorageBucket(properties.storage().bucketName());
        }

        FirebaseApp app = FirebaseApp.initializeApp(optionsBuilder.build());
        log.info("Firebase Admin inicializado correctamente. Proyecto: {}", app.getOptions().getProjectId());
        return app;
    }

    @Bean
    public Firestore firestore(FirebaseApp firebaseApp) {
        return FirestoreClient.getFirestore(firebaseApp);
    }

    @Bean
    public FirebaseAuth firebaseAuth(FirebaseApp firebaseApp) {
        return FirebaseAuth.getInstance(firebaseApp);
    }

    @Bean
    public FirebaseDatabase firebaseDatabase(FirebaseApp firebaseApp) {
        return FirebaseDatabase.getInstance(firebaseApp);
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        return FirebaseMessaging.getInstance(firebaseApp);
    }

    // El JSON en variable de entorno tiene prioridad sobre la ruta (así se
    // configuraba en Heroku; en el droplet se usa la ruta).
    static GoogleCredentials loadCredentials(FirebaseProperties.Credentials credentials) {
        if (credentials.hasJson()) {
            try (InputStream stream = new ByteArrayInputStream(credentials.json().getBytes(StandardCharsets.UTF_8))) {
                return GoogleCredentials.fromStream(stream);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "No se pudo leer la credencial de Firebase de FIREBASE_CREDENTIALS_JSON: el contenido no es un JSON de service account válido",
                        e);
            }
        }

        if (!credentials.hasPath()) {
            throw new IllegalStateException(
                    "Falta la credencial de Firebase: definir firebase.credentials.path "
                            + "(o la variable FIREBASE_CREDENTIALS_PATH) o FIREBASE_CREDENTIALS_JSON");
        }

        Resource resource = new DefaultResourceLoader().getResource(credentials.path());
        if (!resource.exists()) {
            throw new IllegalStateException(describeMissingCredentials(credentials.path(), resource));
        }

        try (InputStream stream = resource.getInputStream()) {
            return GoogleCredentials.fromStream(stream);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "No se pudo leer la credencial de Firebase en '" + credentials.path()
                            + "': el archivo existe pero no es un JSON de service account válido",
                    e);
        }
    }

    // El caso típico es arrancar desde otra carpeta: la ruta por defecto
    // (file:./secrets/...) es relativa al directorio de trabajo, no al proyecto.
    // El mensaje dice dónde se buscó realmente, para que se resuelva de un vistazo.
    static String describeMissingCredentials(String configuredPath, Resource resource) {
        String absolutePath;
        try {
            absolutePath = resource.getFile().getAbsolutePath();
        } catch (IOException e) {
            absolutePath = "(no es un archivo local)";
        }
        String workingDirectory = Path.of("").toAbsolutePath().toString();

        return "No se encontró la credencial de Firebase.\n"
                + "  Ruta configurada:       " + configuredPath + "\n"
                + "  Se buscó en:            " + absolutePath + "\n"
                + "  Directorio de trabajo:  " + workingDirectory + "\n"
                + "Si la ruta es relativa (file:./...), el servidor tiene que arrancar desde la "
                + "carpeta del proyecto (server/taxiapp). En IntelliJ: Run > Edit Configurations > "
                + "Working directory. Alternativa: FIREBASE_CREDENTIALS_PATH con una ruta absoluta.";
    }
}
