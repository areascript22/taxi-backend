package com.areascript.taxiapp.notification.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.firebase.FirebaseProperties;
import com.areascript.taxiapp.notification.domain.Device;
import com.areascript.taxiapp.notification.domain.DeviceRegistry;
import com.areascript.taxiapp.notification.domain.DeviceWriter;
import com.areascript.taxiapp.notification.domain.Recipient;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// El token FCM y el idioma viven junto al perfil: drivers/{uid} y
// passengers/{uid}, campos `fcmToken` y `language`.
@Component
public class FirestoreDeviceRegistry implements DeviceRegistry, DeviceWriter {

    private static final Logger log = LoggerFactory.getLogger(FirestoreDeviceRegistry.class);

    private final Firestore firestore;
    private final long timeoutMillis;

    public FirestoreDeviceRegistry(Firestore firestore, FirebaseProperties firebaseProperties) {
        this.firestore = firestore;
        this.timeoutMillis = firebaseProperties.operationTimeout().toMillis();
    }

    // `update` y no `set`: si el documento no existe falla (NOT_FOUND) en vez de
    // crear un perfil a medias que la app tomaría por un registro completo.
    @Override
    public boolean save(Recipient recipient, Device device) {
        String collection = collectionOf(recipient);
        Map<String, Object> updates = new HashMap<>();
        if (device.deviceToken() != null) {
            updates.put("fcmToken", device.deviceToken());
        }
        if (device.language() != null) {
            updates.put("language", device.language());
        }
        if (updates.isEmpty()) {
            return true;
        }
        try {
            firestore.collection(collection).document(recipient.uid()).update(updates)
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("NotificationDebug | Interrumpido al guardar el dispositivo de {}/{}: {}", collection, recipient.uid(), e.getMessage(), e);
            throw new ExternalServiceException("No se pudo guardar el dispositivo", e);
        } catch (ExecutionException e) {
            if (isNotFound(e)) {
                return false;
            }
            log.error("NotificationDebug | Error al guardar el dispositivo de {}/{}: {}", collection, recipient.uid(), e.getMessage(), e);
            throw new ExternalServiceException("No se pudo guardar el dispositivo", e);
        } catch (TimeoutException e) {
            log.error("NotificationDebug | Timeout al guardar el dispositivo de {}/{}: {}", collection, recipient.uid(), e.getMessage(), e);
            throw new ExternalServiceException("No se pudo guardar el dispositivo", e);
        }
    }

    private static boolean isNotFound(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.google.api.gax.rpc.ApiException api
                    && api.getStatusCode().getCode() == com.google.api.gax.rpc.StatusCode.Code.NOT_FOUND) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains("NOT_FOUND")) {
                return true;
            }
        }
        return false;
    }

    private static String collectionOf(Recipient recipient) {
        return recipient.kind() == Recipient.Kind.DRIVER ? "drivers" : "passengers";
    }

    @Override
    public Optional<Device> findDevice(Recipient recipient) {
        String collection = collectionOf(recipient);
        try {
            DocumentSnapshot snapshot = firestore.collection(collection).document(recipient.uid()).get()
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!snapshot.exists()) {
                return Optional.empty();
            }
            return Optional.of(new Device(snapshot.getString("fcmToken"), snapshot.getString("language")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("NotificationDebug | Interrumpido al buscar el dispositivo de {}/{}: {}", collection, recipient.uid(), e.getMessage(), e);
            throw new ExternalServiceException("No se pudo buscar el dispositivo del destinatario", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("NotificationDebug | Error al buscar el dispositivo de {}/{}: {}", collection, recipient.uid(), e.getMessage(), e);
            throw new ExternalServiceException("No se pudo buscar el dispositivo del destinatario", e);
        }
    }
}
