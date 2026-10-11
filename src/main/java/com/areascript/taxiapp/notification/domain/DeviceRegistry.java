package com.areascript.taxiapp.notification.domain;

import java.util.Optional;

// Puerto de salida: dónde está registrado el dispositivo de un usuario.
//
// El idioma sale de acá, del DESTINATARIO, y no del request: el push casi
// siempre va dirigido al otro participante (el conductor acepta -> el push es
// para el pasajero), así que el idioma de quien dispara la acción es el de la
// persona equivocada.
//
// Vacío si el usuario no existe. Un fallo de almacenamiento se traduce a
// ExternalServiceException (common.error).
public interface DeviceRegistry {

    Optional<Device> findDevice(Recipient recipient);
}
