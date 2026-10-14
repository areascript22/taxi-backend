package com.areascript.taxiapp.chat.domain;

import java.util.Optional;

// Puerto de salida: la carrera del pasajero, vista desde el chat. La
// implementa la feature de viajes (el chat no conoce dónde ni cómo se guarda
// una carrera).
//
// Vacío si el pasajero no tiene ninguna solicitud. Un fallo de lectura se
// traduce a ExternalServiceException (common.error).
public interface ChatRideLookup {

    Optional<ChatRide> findByPassenger(String passengerId);
}
