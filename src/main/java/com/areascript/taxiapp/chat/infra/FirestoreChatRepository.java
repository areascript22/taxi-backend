package com.areascript.taxiapp.chat.infra;

import com.areascript.taxiapp.chat.domain.ChatMessage;
import com.areascript.taxiapp.chat.domain.ChatRepository;
import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.firebase.FirebaseProperties;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Chats en Firestore: chats/{rideId} (el hilo, que solo escribe el backend) y
// chats/{rideId}/messages/{messageId}. Ver firebase/firestore.rules para el
// modelo completo y quién puede leer qué.
@Component
public class FirestoreChatRepository implements ChatRepository {

    private static final Logger log = LoggerFactory.getLogger(FirestoreChatRepository.class);
    private static final String CHATS_COLLECTION = "chats";
    private static final String MESSAGES_SUBCOLLECTION = "messages";

    private final Firestore firestore;
    private final long timeoutMillis;

    public FirestoreChatRepository(Firestore firestore, FirebaseProperties firebaseProperties) {
        this.firestore = firestore;
        this.timeoutMillis = firebaseProperties.operationTimeout().toMillis();
    }

    @Override
    public void createThread(String rideId, String passengerId, String driverId) {
        Map<String, Object> thread = new HashMap<>();
        thread.put("passengerId", passengerId);
        thread.put("driverId", driverId);
        thread.put("createdAt", Timestamp.now());

        await(
                firestore.collection(CHATS_COLLECTION).document(rideId).set(thread),
                "No se pudo crear el hilo de chat de rideId=" + rideId);
    }

    @Override
    public void addMessage(ChatMessage message) {
        Map<String, Object> data = new HashMap<>();
        data.put("senderId", message.senderId());
        data.put("senderRole", message.senderRole().wireValue());
        data.put("text", message.text());
        data.put("createdAt", toTimestamp(message.createdAt()));
        data.put("expireAt", toTimestamp(message.expireAt()));

        await(
                firestore.collection(CHATS_COLLECTION)
                        .document(message.rideId())
                        .collection(MESSAGES_SUBCOLLECTION)
                        .add(data),
                "No se pudo enviar el mensaje en rideId=" + message.rideId());
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }

    private <T> void await(ApiFuture<T> future, String failureMessage) {
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("ChatDebug | Interrumpido: {}: {}", failureMessage, e.getMessage(), e);
            throw new ExternalServiceException(failureMessage, e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("ChatDebug | {}: {}", failureMessage, e.getMessage(), e);
            throw new ExternalServiceException(failureMessage, e);
        }
    }
}
