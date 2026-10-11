package com.areascript.taxiapp.user.domain;

import java.util.Optional;
import java.util.UUID;

// Puerto de salida: los perfiles de cada app que cuelgan de una persona.
//
// Cualquier fallo de almacenamiento se traduce a ExternalServiceException
// (common.error).
public interface UserProfiles {

    Optional<DriverProfile> findDriver(UUID userId);

    Optional<PassengerProfile> findPassenger(UUID userId);

    // Guarda el token de notificaciones y/o el idioma del dispositivo de esa app.
    // Un argumento null significa "no cambiar". Devuelve false si la persona no
    // tiene perfil en esa app (no se crea uno: el perfil nace con el registro).
    boolean saveDevice(UUID userId, AppProfile app, String fcmToken, String language);
}
