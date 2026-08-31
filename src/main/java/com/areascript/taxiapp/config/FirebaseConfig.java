package com.areascript.taxiapp.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessaging;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${firebase.credentials.path:}")
    private String credentialsPath;

    @Value("${FIREBASE_CREDENTIALS_JSON:}")
    private String credentialsJson;

    // URL de la Realtime Database (ej. https://<project-id>-default-rtdb.firebaseio.com
    // o la variante regional que Firebase Console muestre para el proyecto).
    // Sin esto, FirebaseDatabase.getInstance() no puede resolver a qué
    // instancia de RTDB conectarse.
    @Value("${firebase.database.url:}")
    private String databaseUrl;

    @Bean
    public FirebaseApp firebaseApp() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        try {
            try (InputStream credentialsStream = credentialsJson.isBlank()
                    ? new DefaultResourceLoader().getResource(credentialsPath).getInputStream()
                    : new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8))) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream);
                FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder().setCredentials(credentials);
                if (credentials instanceof ServiceAccountCredentials serviceAccountCredentials) {
                    optionsBuilder.setProjectId(serviceAccountCredentials.getProjectId());
                }
                if (!databaseUrl.isBlank()) {
                    optionsBuilder.setDatabaseUrl(databaseUrl);
                }
                FirebaseApp app = FirebaseApp.initializeApp(optionsBuilder.build());
                log.info("Firebase Admin inicializado correctamente. Proyecto: {}", app.getOptions().getProjectId());
                return app;
            }
        } catch (Exception e) {
            log.error("No se pudo inicializar Firebase Admin (credentials.path='{}'): {}", credentialsPath, e.getMessage(), e);
            throw new IllegalStateException("Fallo al inicializar Firebase Admin", e);
        }
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
}
