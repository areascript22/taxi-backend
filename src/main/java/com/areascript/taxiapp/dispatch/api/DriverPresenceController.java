package com.areascript.taxiapp.dispatch.api;

import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.common.security.AuthenticatedUser;
import com.areascript.taxiapp.dispatch.application.DriverAvailabilityService;
import com.areascript.taxiapp.dispatch.application.DriverLocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// La presencia del conductor: el interruptor "en línea" y su ubicación. El
// conductor sale siempre del token, nunca de un campo del request.
@RestController
@RequestMapping("/api/me/driver")
public class DriverPresenceController {

    private final DriverAvailabilityService availability;
    private final DriverLocationService location;

    public DriverPresenceController(DriverAvailabilityService availability, DriverLocationService location) {
        this.availability = availability;
        this.location = location;
    }

    // 204. 403 si no está habilitado para ponerse en línea (no registrado,
    // pendiente de aprobación o bloqueado); 404 si su perfil todavía no está
    // listo. Desconectarse siempre se acepta.
    @PutMapping("/availability")
    public ResponseEntity<Void> setAvailability(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody AvailabilityRequest request) {
        if (request.online() == null) {
            throw new InvalidInputException("Se requiere el campo 'online'");
        }
        if (request.online()) {
            availability.goOnline(user.uid());
        } else {
            availability.goOffline(user.uid());
        }
        return ResponseEntity.noContent().build();
    }

    // 204. 400 si la ubicación es inválida; 409 si no está en línea o la carrera
    // ya no está en curso; 403 si no es el conductor de esa carrera.
    @PutMapping("/location")
    public ResponseEntity<Void> reportLocation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody LocationRequest request) {
        if (request.latitude() == null || request.longitude() == null) {
            throw new InvalidInputException("Se requieren 'latitude' y 'longitude'");
        }
        location.report(user.uid(), request.latitude(), request.longitude(), request.passengerId());
        return ResponseEntity.noContent().build();
    }
}
