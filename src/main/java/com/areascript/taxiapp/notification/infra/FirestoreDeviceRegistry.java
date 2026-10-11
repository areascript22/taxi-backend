package com.areascript.taxiapp.notification.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.firebase.FirebaseProperties;
import com.areascript.taxiapp.notification.domain.Device;
import com.areascript.taxiapp.notification.domain.DeviceRegistry;
import com.areascript.taxiapp.notification.domain.Recipient;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// El token FCM y el idioma viven junto al perfil: drivers/{uid} y
// passengers/{uid}, campos `fcmToken` y `language`.
@Component
public class FirestoreDeviceRegistry implements DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(FirestoreDeviceRegistry.class);

    private final Firestore firestore;
    private final long timeoutMillis;

    public FirestoreDeviceRegistry(Firestore firestore, FirebaseProperties firebaseProperties) {
        this.firestore = firestore;
        this.timeoutMillis = firebaseProperties.operationTimeout().toMillis();
    }

    @Override
    public Optional<Device> findDevice(Recipient recipient) {
        String collection = recipient.kind() == Recipient.Kind.DRIVER ? "drivers" : "passengers";
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
