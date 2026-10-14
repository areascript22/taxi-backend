package com.areascript.taxiapp.user.api;

import com.areascript.taxiapp.common.security.AuthenticatedUser;
import com.areascript.taxiapp.user.application.CurrentUserService;
import com.areascript.taxiapp.user.application.DeviceRegistrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Todo lo "mío": la identidad y los perfiles de quien hace el request. La
// persona sale siempre del token, nunca de un campo del request.
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final CurrentUserService currentUser;
    private final DeviceRegistrationService devices;

    public MeController(CurrentUserService currentUser, DeviceRegistrationService devices) {
        this.currentUser = currentUser;
        this.devices = devices;
    }

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return MeResponse.from(currentUser.me(user.uid(), user.email(), user.name()));
    }

    // 204. 400 si app/idioma/token son inválidos; 404 si todavía no hay perfil en
    // esa app.
    @PutMapping("/device")
    public ResponseEntity<Void> registerDevice(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody DeviceRequest request) {
        devices.register(user.uid(), user.email(), user.name(),
                request.app(), request.fcmToken(), request.language());
        return ResponseEntity.noContent().build();
    }
}
