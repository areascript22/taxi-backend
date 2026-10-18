package com.areascript.taxiapp.chat.api;

import com.areascript.taxiapp.chat.application.ChatService;
import com.areascript.taxiapp.common.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// La URL se mantiene bajo /api/rides: es el contrato con las apps (el chat es
// "de una carrera"), aunque el código viva en su propia feature.
@RestController
@RequestMapping("/api/rides")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    // El rol de quien escribe (driver/passenger) se deriva del uid del token
    // verificado, nunca del cliente, y el push a la otra parte lo dispara el
    // backend. 400 texto vacío, 404 sin carrera, 409 carrera sin conductor o
    // terminada, 403 no participante: los traduce GlobalExceptionHandler.
    @PostMapping("/{passengerId}/messages")
    public ResponseEntity<Void> sendChatMessage(
            @PathVariable String passengerId,
            @RequestBody SendChatMessageRequest body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        chatService.sendMessage(passengerId, user.uid(), body.text());
        return ResponseEntity.noContent().build();
    }
}
