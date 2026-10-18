package com.areascript.taxiapp.chat.application;

import com.areascript.taxiapp.chat.domain.ChatMessage;
import com.areascript.taxiapp.chat.domain.ChatRepository;
import com.areascript.taxiapp.chat.domain.ChatRide;
import com.areascript.taxiapp.chat.domain.ChatRideLookup;
import com.areascript.taxiapp.chat.domain.SenderRole;
import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.common.error.ConflictException;
import com.areascript.taxiapp.common.error.ForbiddenException;
import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.notification.domain.PushMessage;
import com.areascript.taxiapp.notification.domain.PushRoutes;
import com.areascript.taxiapp.notification.domain.Recipient;
import com.areascript.taxiapp.notification.domain.UserNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

// Chat entre pasajero y conductor durante un viaje activo.
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String PUSH_TYPE = "chat_message";
    private static final String TITLE_FROM_DRIVER = "push.chat.fromDriver.title";
    private static final String TITLE_FROM_PASSENGER = "push.chat.fromPassenger.title";

    private final ChatRideLookup rides;
    private final ChatRepository chats;
    private final UserNotifier notifier;
    private final TaxiappProperties.Chat settings;
    private final Clock clock;

    public ChatService(
            ChatRideLookup rides,
            ChatRepository chats,
            UserNotifier notifier,
            TaxiappProperties properties,
            Clock clock
    ) {
        this.rides = rides;
        this.chats = chats;
        this.notifier = notifier;
        this.settings = properties.chat();
        this.clock = clock;
    }

    // Escribe un mensaje y avisa por push al otro participante.
    //
    // Deriva el rol (driver/passenger) del uid del token verificado -- el
    // cliente nunca dice quién es, solo manda el texto. El rideId lo resuelve
    // el backend desde la carrera (fuente de verdad), nunca confía en un
    // rideId que mande el cliente.
    public void sendMessage(String passengerId, String senderUid, String text) {
        // Primero lo más barato: un texto vacío ni siquiera consulta la carrera.
        if (text == null || text.isBlank()) {
            throw new InvalidInputException("El mensaje no puede estar vacío");
        }

        ChatRide ride = rides.findByPassenger(passengerId).orElseThrow(() -> new NotFoundException(
                "No existe una solicitud de carrera activa para passengerId: " + passengerId));

        if (!ride.active()) {
            // 'pending' (sin conductor asignado todavía) también cae acá: el
            // chat solo tiene sentido una vez que hay alguien del otro lado
            // para leerlo.
            throw new ConflictException(
                    "La carrera de passengerId=" + passengerId + " ya estaba cancelada o finalizada");
        }

        SenderRole senderRole;
        Recipient recipient;
        if (senderUid.equals(passengerId)) {
            senderRole = SenderRole.PASSENGER;
            recipient = Recipient.driver(ride.driverId());
        } else if (senderUid.equals(ride.driverId())) {
            senderRole = SenderRole.DRIVER;
            recipient = Recipient.passenger(passengerId);
        } else {
            throw new ForbiddenException(
                    "El llamador no está autorizado para esta operación sobre passengerId=" + passengerId);
        }

        String trimmed = text.trim();
        if (trimmed.length() > settings.maxMessageLength()) {
            trimmed = trimmed.substring(0, settings.maxMessageLength());
        }

        Instant now = clock.instant();
        chats.addMessage(new ChatMessage(
                ride.rideId(),
                senderUid,
                senderRole,
                trimmed,
                now,
                now.plus(settings.retention())
        ));
        log.info("ChatDebug | Mensaje enviado en rideId={} por senderRole={}", ride.rideId(), senderRole.wireValue());

        notifier.notifyUser(recipient, pushFor(senderRole, trimmed, ride.rideId()));
    }

    private PushMessage pushFor(SenderRole senderRole, String text, String rideId) {
        int previewLength = settings.pushPreviewLength();
        String preview = text.length() > previewLength ? text.substring(0, previewLength) + "…" : text;

        boolean fromDriver = senderRole == SenderRole.DRIVER;
        return PushMessage.withUserText(
                fromDriver ? TITLE_FROM_DRIVER : TITLE_FROM_PASSENGER,
                preview,
                fromDriver ? PushRoutes.PASSENGER_RIDE_TRACKING : PushRoutes.DRIVER_BOOKING,
                PUSH_TYPE,
                rideId
        );
    }
}
