package com.areascript.taxiapp.user.api;

import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.common.security.AuthenticatedUser;
import com.areascript.taxiapp.user.application.ProfileRegistrationService;
import com.areascript.taxiapp.user.domain.DriverProfileData;
import com.areascript.taxiapp.user.domain.PassengerProfileData;
import com.areascript.taxiapp.user.domain.VehicleData;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Alta y edición del perfil propio. Idempotente (PUT): crear y editar son la
// misma llamada, así un doble toque o un reintento por timeout no duplica nada.
// La persona sale siempre del token.
//
// Responde con el perfil completo (el mismo cuerpo que GET /api/me): la app no
// tiene que volver a pedirlo.
@RestController
@RequestMapping("/api/me")
public class ProfileController {

    private final ProfileRegistrationService registration;

    public ProfileController(ProfileRegistrationService registration) {
        this.registration = registration;
    }

    // 400 si falta algo o no es válido (incluida una foto que no es la de esta
    // persona). El perfil nace "pending": un administrador lo aprueba.
    @PutMapping("/driver-profile")
    public MeResponse saveDriverProfile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody DriverProfileRequest request) {
        DriverProfileRequest.Vehicle vehicle = request.vehicle();
        if (vehicle == null || vehicle.year() == null) {
            throw new InvalidInputException("Faltan los datos del vehículo");
        }

        DriverProfileData data = new DriverProfileData(
                request.firstName(),
                request.lastName(),
                request.phoneNumber(),
                request.photoUrl(),
                new VehicleData(
                        vehicle.plate(),
                        vehicle.brand(),
                        vehicle.model(),
                        vehicle.year(),
                        vehicle.color(),
                        vehicle.registrationNumber()));

        return MeResponse.from(
                registration.saveDriverProfile(user.uid(), user.email(), user.name(), data));
    }

    @PutMapping("/passenger-profile")
    public MeResponse savePassengerProfile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody PassengerProfileRequest request) {
        PassengerProfileData data = new PassengerProfileData(
                request.firstName(), request.lastName(), request.photoUrl());

        return MeResponse.from(
                registration.savePassengerProfile(user.uid(), user.email(), user.name(), data));
    }
}
