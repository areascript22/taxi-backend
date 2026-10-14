package com.areascript.taxiapp.characterization.support;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.firebase.FirebaseProperties;
import com.areascript.taxiapp.notification.domain.Device;
import com.areascript.taxiapp.notification.domain.DeviceRegistry;
import com.areascript.taxiapp.notification.domain.Recipient;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// DOBLE DE PRUEBA del puerto DeviceRegistry para los tests HTTP, que corren sin
// Postgres: lee fcmToken e idioma de los documentos del FakeFirestore
// (drivers/{uid} y passengers/{uid}). En produccion lo implementa
// PostgresDeviceRegistry.
public class DocumentStoreDeviceRegistry implements DeviceRegistry {

    private final Firestore firestore;
    private final long timeoutMillis;

    public DocumentStoreDeviceRegistry(Firestore firestore, FirebaseProperties firebaseProperties) {
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
            throw new ExternalServiceException("No se pudo buscar el dispositivo del destinatario", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new ExternalServiceException("No se pudo buscar el dispositivo del destinatario", e);
        }
    }
}