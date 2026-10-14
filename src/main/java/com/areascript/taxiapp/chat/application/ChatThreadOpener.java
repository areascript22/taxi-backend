package com.areascript.taxiapp.chat.application;

import com.areascript.taxiapp.chat.domain.ChatRepository;
import com.areascript.taxiapp.ride.domain.RideAccepted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Abre el hilo de chat cuando un conductor acepta una carrera. Reacciona al
// evento RideAccepted: la feature de viajes no sabe que existe un chat.
//
// El listener es síncrono, así que el hilo ya existe cuando el conductor
// recibe la respuesta de "aceptar" -- el pasajero puede escribirle de
// inmediato.
@Component
public class ChatThreadOpener {

    private static final Logger log = LoggerFactory.getLogger(ChatThreadOpener.class);

    private final ChatRepository chats;

    public ChatThreadOpener(ChatRepository chats) {
        this.chats = chats;
    }

    // No relanza si falla -- igual que un push que falla, no debe revertir una
    // carrera ya aceptada; en el peor caso el chat queda no disponible para
    // esa carrera puntual.
    @EventListener
    public void on(RideAccepted event) {
        try {
            chats.createThread(event.rideId(), event.passengerId(), event.driverId());
        } catch (RuntimeException e) {
            log.error("ChatDebug | No se pudo abrir el hilo de chat de rideId={}: {}", event.rideId(), e.getMessage(), e);
        }
    }
}
