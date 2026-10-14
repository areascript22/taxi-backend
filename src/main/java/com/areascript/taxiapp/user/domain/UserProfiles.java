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

    // Alta o edición del perfil de conductor, en una sola transacción: sus
    // datos personales (nombre y apellido viven en la persona, el teléfono y la
    // foto en el perfil) y su vehículo.
    //
    // Al CREAR el perfil el servidor fija la aprobación en "pending": el cliente
    // no la decide. Al editar no se toca (aprobación, bloqueo, rol y
    // calificación son del servidor).
    //
    // El vehículo: si el conductor ya tiene uno se actualiza; si no, se crea y
    // queda como su vehículo actual. Cambiar la placa o la matrícula vuelve la
    // verificación del vehículo a "pending" (es otro auto, o al menos otro
    // documento); cambiar el color, por ejemplo, no.
    //
    // `email` solo se usa si la persona todavía no lo tiene guardado.
    void saveDriverProfile(UUID userId, String email, DriverProfileData data);

    // Alta o edición del perfil de pasajero. Al crearlo queda "active".
    void savePassengerProfile(UUID userId, String email, PassengerProfileData data);
}
