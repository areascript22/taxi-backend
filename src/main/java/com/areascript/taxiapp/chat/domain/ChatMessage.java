package com.areascript.taxiapp.chat.domain;

import java.time.Instant;

// Un mensaje ya validado y listo para guardar. `expireAt` es el momento a
// partir del cual el almacenamiento puede borrarlo (la política TTL de
// Firestore se apoya en ese campo).
public record ChatMessage(
        String rideId,
        String senderId,
        SenderRole senderRole,
        String text,
        Instant createdAt,
        Instant expireAt
) {
}
