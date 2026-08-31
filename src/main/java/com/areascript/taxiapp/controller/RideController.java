package com.areascript.taxiapp.controller;

import com.areascript.taxiapp.dto.AcceptRideRequest;
import com.areascript.taxiapp.security.FirebaseSecurityUtils;
import com.areascript.taxiapp.service.RideAcceptException;
import com.areascript.taxiapp.service.RideAlreadyAssignedException;
import com.areascript.taxiapp.service.RideNotFoundException;
import com.areascript.taxiapp.service.RideService;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.http.HttpServletRequest;
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

    @PostMapping("/{passengerId}/accept")
    public ResponseEntity<Void> acceptRide(
            @PathVariable String passengerId,
            @RequestBody AcceptRideRequest body,
            HttpServletRequest request
    ) {
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
}
