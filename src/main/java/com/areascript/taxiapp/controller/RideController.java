package com.areascript.taxiapp.controller;

import com.areascript.taxiapp.dto.AcceptRideRequest;
import com.areascript.taxiapp.security.FirebaseSecurityUtils;
import com.areascript.taxiapp.service.RideAcceptException;
import com.areascript.taxiapp.service.RideAlreadyAssignedException;
import com.areascript.taxiapp.service.RideAlreadyFinishedException;
import com.areascript.taxiapp.service.RideCancelException;
import com.areascript.taxiapp.service.RideCompleteException;
import com.areascript.taxiapp.service.RideForbiddenException;
import com.areascript.taxiapp.service.RideNotFoundException;
import com.areascript.taxiapp.service.RideService;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rides")
public class RideController {

    private final RideService rideService;

    public RideController(RideService rideService) {
        this.rideService = rideService;
    }

    private static final Logger log = LoggerFactory.getLogger(RideController.class);

    @PostMapping("/{passengerId}/accept")
    public ResponseEntity<Void> acceptRide(
            @PathVariable String passengerId,
            @RequestBody AcceptRideRequest body,
            HttpServletRequest request
    ) {

        log.info("Calling accept ride");
        FirebaseToken token = FirebaseSecurityUtils.getToken(request);
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            rideService.acceptRide(
                    passengerId,
                    token.getUid(),
                    token.getEmail(),
                    token.getName(),
                    token.getPicture(),
                    body.latitude(),
                    body.longitude()
            );
            return ResponseEntity.noContent().build();
        } catch (RideNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (RideAlreadyAssignedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (RideAcceptException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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
            HttpServletRequest request
    ) {
        FirebaseToken token = FirebaseSecurityUtils.getToken(request);
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            rideService.cancelRide(passengerId, token.getUid());
            return ResponseEntity.noContent().build();
        } catch (RideNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (RideForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (RideAlreadyFinishedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (RideCancelException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // Solo el conductor asignado puede finalizar el viaje (verificado
    // server-side con el token, no confiando en el cliente).
    @PostMapping("/{passengerId}/complete")
    public ResponseEntity<Void> completeTrip(
            @PathVariable String passengerId,
            HttpServletRequest request
    ) {
        FirebaseToken token = FirebaseSecurityUtils.getToken(request);
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            rideService.completeTrip(passengerId, token.getUid());
            return ResponseEntity.noContent().build();
        } catch (RideNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (RideForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (RideAlreadyFinishedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (RideCompleteException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
