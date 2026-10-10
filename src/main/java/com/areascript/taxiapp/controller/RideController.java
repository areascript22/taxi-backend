package com.areascript.taxiapp.controller;

import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.common.security.AuthenticatedUser;
import com.areascript.taxiapp.dto.AcceptRideRequest;
import com.areascript.taxiapp.dto.RequestRideRequest;
import com.areascript.taxiapp.dto.SendChatMessageRequest;
import com.areascript.taxiapp.service.RideService;
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
// GlobalExceptionHandler a partir de la excepción que lanza RideService.
//
// No hay @PreAuthorize acá porque ningún endpoint exige un rol del token:
// "quién puede aceptar" (un conductor aprobado y no bloqueado) vive en
// RideService, porque depende del documento del conductor en Firestore y no
// de los claims del token.
@RestController
@RequestMapping("/api/rides")
public class RideController {

    private final RideService rideService;

    public RideController(RideService rideService) {
        this.rideService = rideService;
    }

    // Reemplaza el .set() directo que hacía passenger_app sobre Realtime
    // Database al pedir un taxi: pasa por el backend para poder agendar la
    // auto-cancelación (RideService.expireIfStillPending) y para que el
    // nombre/foto del pasajero salgan del token verificado, no del cliente.
    @PostMapping("/request")
    public ResponseEntity<Void> requestRide(
            @RequestBody RequestRideRequest body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        rideService.requestRide(
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
        rideService.acceptRide(
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
    // conductor: el rol de quien cancela se deriva del uid del token
    // verificado (si coincide con passengerId es el pasajero, si coincide
    // con el conductor ya asignado es el conductor), igual que acceptRide
    // deriva la identidad del conductor del token en vez de confiar en el
    // cliente.
    @PostMapping("/{passengerId}/cancel")
    public ResponseEntity<Void> cancelRide(
            @PathVariable String passengerId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        rideService.cancelRide(passengerId, user.uid());
        return ResponseEntity.noContent().build();
    }

    // Solo el conductor asignado puede finalizar el viaje (verificado
    // server-side con el token, no confiando en el cliente).
    @PostMapping("/{passengerId}/complete")
    public ResponseEntity<Void> completeTrip(
            @PathVariable String passengerId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        rideService.completeTrip(passengerId, user.uid());
        return ResponseEntity.noContent().build();
    }

    // Mensajería de chat entre pasajero y conductor durante un viaje activo.
    // Mismo criterio que cancelRide/completeTrip: el rol de quien escribe
    // (driver/passenger) se deriva del uid del token verificado, nunca del
    // cliente, y el push a la otra parte lo dispara el backend.
    @PostMapping("/{passengerId}/messages")
    public ResponseEntity<Void> sendChatMessage(
            @PathVariable String passengerId,
            @RequestBody SendChatMessageRequest body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        if (body.text() == null || body.text().isBlank()) {
            throw new InvalidInputException("El mensaje no puede estar vacío");
        }

        rideService.sendChatMessage(passengerId, user.uid(), body.text());
        return ResponseEntity.noContent().build();
    }

    // Reemplaza la lectura directa que hacía passenger_app sobre su propio
    // nodo de Realtime Database al reabrir la app, para decidir si debe
    // resumir en RideTrackingScreen. El passengerId sale del token, nunca
    // del cliente.
    @GetMapping("/passenger/active")
    public ResponseEntity<Map<String, Object>> getActivePassengerRide(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Map<String, Object> ride = rideService.findActiveRideForPassenger(user.uid());
        return ride == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(ride);
    }

    // Reemplaza el escaneo completo de /taxi_requests que hacía driver_app
    // client-side al reabrir la app, para decidir si debe resumir en
    // TripScreen. El driverUid sale del token, nunca del cliente.
    @GetMapping("/driver/active")
    public ResponseEntity<Map<String, Object>> getActiveDriverRide(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Map<String, Object> ride = rideService.findActiveRideForDriver(user.uid());
        return ride == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(ride);
    }
}
