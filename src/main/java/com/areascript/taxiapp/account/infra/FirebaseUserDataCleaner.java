package com.areascript.taxiapp.account.infra;

import com.areascript.taxiapp.account.domain.UserDataCleaner;
import com.areascript.taxiapp.common.firebase.FirebaseProperties;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.StorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Implementación de UserDataCleaner sobre Firestore y Firebase Storage:
// borra el perfil (drivers/passengers), el vehículo, la foto y los chats del
// usuario. Ver el puerto para el contrato y para por qué la cuenta de
// autenticación queda a cargo de cada flujo.
//
// Antes de existir esto, borrar a un conductor solo eliminaba Auth + el doc de
// Firestore, sin tocar el vehículo, la foto de Storage ni los chats.
@Component
public class FirebaseUserDataCleaner implements UserDataCleaner {

    private static final Logger log = LoggerFactory.getLogger(FirebaseUserDataCleaner.class);
    private static final String DRIVERS_COLLECTION = "drivers";
    private static final String PASSENGERS_COLLECTION = "passengers";
    private static final String VEHICLES_COLLECTION = "vehicles";
    private static final String CHATS_COLLECTION = "chats";
    private static final String MESSAGES_SUBCOLLECTION = "messages";

    private final Firestore firestore;
    private final FirebaseApp firebaseApp;
    private final long timeoutMillis;

    public FirebaseUserDataCleaner(Firestore firestore, FirebaseApp firebaseApp, FirebaseProperties firebaseProperties) {
        this.firestore = firestore;
        this.firebaseApp = firebaseApp;
        this.timeoutMillis = firebaseProperties.operationTimeout().toMillis();
    }

    // Cada paso es best-effort (se loguea y se sigue) salvo el borrado final
    // de los documentos drivers/passengers -- el objetivo es dejar la cuenta
    // lo más limpia posible incluso si, por ejemplo, Storage no está
    // configurado todavía en este entorno (ver FirebaseConfig.storageBucket).
    @Override
    public void wipe(String uid) {
        deleteChatsForUser(uid);

        DocumentSnapshot driverSnapshot = getDocument(DRIVERS_COLLECTION, uid);
        if (driverSnapshot != null && driverSnapshot.exists()) {
            String vehicleId = driverSnapshot.getString("vehicleId");
            if (vehicleId != null && !vehicleId.isBlank()) {
                deleteDocument(VEHICLES_COLLECTION, vehicleId);
            }
            deleteStorageObject("driver_profile_photos/" + uid + ".jpg");
            deleteDocument(DRIVERS_COLLECTION, uid);
        }

        DocumentSnapshot passengerSnapshot = getDocument(PASSENGERS_COLLECTION, uid);
        if (passengerSnapshot != null && passengerSnapshot.exists()) {
            deleteStorageObject("passenger_profile_photos/" + uid + ".jpg");
            deleteDocument(PASSENGERS_COLLECTION, uid);
        }

        log.info("AccountCleanupDebug | Datos de uid={} limpiados (chats, vehículo, foto, documento)", uid);
    }

    private void deleteChatsForUser(String uid) {
        deleteChatsWhere("driverId", uid);
        deleteChatsWhere("passengerId", uid);
    }

    // Un chat queda identificado por rideId, no por uid -- un mismo usuario
    // puede haber participado en varios a lo largo del tiempo (uno por
    // carrera), así que hace falta una query por rol en vez de una lectura
    // puntual por id (ver comentario de 'chats' en firestore.rules).
    private void deleteChatsWhere(String field, String uid) {
        List<QueryDocumentSnapshot> chatDocs;
        try {
            chatDocs = firestore.collection(CHATS_COLLECTION)
                    .whereEqualTo(field, uid)
                    .get()
                    .get(timeoutMillis, TimeUnit.MILLISECONDS)
                    .getDocuments();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("AccountCleanupDebug | Interrumpido al buscar chats ({}={}): {}", field, uid, e.getMessage(), e);
            return;
        } catch (ExecutionException | TimeoutException e) {
            log.error("AccountCleanupDebug | Error al buscar chats ({}={}): {}", field, uid, e.getMessage(), e);
            return;
        }

        for (QueryDocumentSnapshot chatDoc : chatDocs) {
            deleteMessagesSubcollection(chatDoc.getId());
            deleteDocument(CHATS_COLLECTION, chatDoc.getId());
        }
    }

    private void deleteMessagesSubcollection(String rideId) {
        try {
            List<QueryDocumentSnapshot> messages = firestore.collection(CHATS_COLLECTION)
                    .document(rideId)
                    .collection(MESSAGES_SUBCOLLECTION)
                    .get()
                    .get(timeoutMillis, TimeUnit.MILLISECONDS)
                    .getDocuments();
            for (QueryDocumentSnapshot message : messages) {
                message.getReference().delete().get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("AccountCleanupDebug | Interrumpido al borrar mensajes de rideId={}: {}", rideId, e.getMessage(), e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("AccountCleanupDebug | Error al borrar mensajes de rideId={}: {}", rideId, e.getMessage(), e);
        }
    }

    private DocumentSnapshot getDocument(String collection, String docId) {
        try {
            return firestore.collection(collection).document(docId).get().get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("AccountCleanupDebug | Interrumpido al leer {}/{}: {}", collection, docId, e.getMessage(), e);
            return null;
        } catch (ExecutionException | TimeoutException e) {
            log.error("AccountCleanupDebug | Error al leer {}/{}: {}", collection, docId, e.getMessage(), e);
            return null;
        }
    }

    private void deleteDocument(String collection, String docId) {
        try {
            firestore.collection(collection).document(docId).delete().get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("AccountCleanupDebug | Interrumpido al borrar {}/{}: {}", collection, docId, e.getMessage(), e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("AccountCleanupDebug | Error al borrar {}/{}: {}", collection, docId, e.getMessage(), e);
        }
    }

    // Best-effort a propósito: si el bucket no está configurado (ej. prod
    // todavía sin Storage, ver FirebaseConfig.storageBucket) o el objeto no
    // existe, solo se loguea -- nunca debe tumbar el resto del borrado de
    // cuenta por un problema puntual de la foto de perfil.
    private void deleteStorageObject(String objectPath) {
        try {
            Bucket bucket = StorageClient.getInstance(firebaseApp).bucket();
            Blob blob = bucket.get(objectPath);
            if (blob == null) {
                log.info("AccountCleanupDebug | No había foto en Storage para '{}'", objectPath);
                return;
            }
            blob.delete();
            log.info("AccountCleanupDebug | Foto '{}' borrada de Storage", objectPath);
        } catch (Exception e) {
            log.warn("AccountCleanupDebug | No se pudo borrar '{}' de Storage (¿bucket no configurado?): {}", objectPath, e.getMessage());
        }
    }
}
