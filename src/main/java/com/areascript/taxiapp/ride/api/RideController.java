package com.areascript.taxiapp.ride.api;

import com.areascript.taxiapp.common.security.AuthenticatedUser;
import com.areascript.taxiapp.ride.application.RideLifecycleService;
import com.areascript.taxiapp.ride.application.RideQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// La identidad llega del token ya verificado (@AuthenticationPrincipal): el
// cliente nunca dice quién es. Los errores de negocio (404 sin solicitud, 409
// carrera ya tomada, 403 no participante, 500 Firebase) los traduce
// GlobalExceptionHandler a partir de la excepción que lanzan los casos de uso.
//
// No hay @PreAuthorize acá porque ningún endpoint exige un rol del token:
// "quién puede aceptar" (un conductor aprobado y no bloqueado) lo decide
// RideLifecycleService, porque depende del perfil del conductor y no de los
// claims del token.
@RestController
@RequestMapping("/api/rides")
public class RideController {

    private final RideLifecycleService lifecycle;
    private final RideQueryService queries;

    public RideController(RideLifecycleService lifecycle, RideQueryService queries) {
        this.lifecycle = lifecycle;
        this.queries = queries;
    }

    @PostMapping("/request")
    public ResponseEntity<Void> requestRide(
            @RequestBody RequestRideRequest body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        lifecycle.requestRide(
                user.uid(),
                user.name(),
                user.picture(),
                body.latitude(),
                body.longitude(),
                body.address(),
                body.sector()
        );
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{passengerId}/accept")
    public ResponseEntity<Void> acceptRide(
            @PathVariable String passengerId,
            @RequestBody AcceptRideRequest body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        lifecycle.acceptRide(
                passengerId,
                user.uid(),
                user.email(),
                user.name(),
                user.picture(),
                body.latitude(),
                body.longitude()
        );
        return ResponseEntity.noContent().build();
    }

    // Único endpoint para cancelar, usado tanto por el pasajero como por el
    // conductor: el rol de quien cancela se deriva del uid del token.
    @PostMapping("/{passengerId}/cancel")
    public ResponseEntity<Void> cancelRide(
            @PathVariable String passengerId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        lifecycle.cancelRide(passengerId, user.uid());
        return ResponseEntity.noContent().build();
    }

    // Solo el conductor asignado puede finalizar el viaje.
    @PostMapping("/{passengerId}/complete")
    public ResponseEntity<Void> completeTrip(
            @PathVariable String passengerId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        lifecycle.completeTrip(passengerId, user.uid());
        return ResponseEntity.noContent().build();
    }

    // Lo llama passenger_app al reabrir, para decidir si debe resumir en
    // RideTrackingScreen. El passengerId sale del token, nunca del cliente.
    @GetMapping("/passenger/active")
    public ResponseEntity<Map<String, Object>> getActivePassengerRide(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return queries.findActiveRideForPassenger(user.uid())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // Lo llama driver_app al reabrir, para decidir si debe resumir en
    // TripScreen. El driverUid sale del token, nunca del cliente.
    @GetMapping("/driver/active")
    public ResponseEntity<Map<String, Object>> getActiveDriverRide(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return queries.findActiveRideForDriver(user.uid())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
