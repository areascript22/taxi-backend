package com.areascript.taxiapp.notification.domain;

// Puerto de salida: guardar el dispositivo de un usuario. Aparte de
// DeviceRegistry porque quien lee (enviar un push) y quien escribe (registrar el
// dispositivo) son casos de uso distintos.
//
// Un fallo de almacenamiento se traduce a ExternalServiceException
// (common.error).
public interface DeviceWriter {

    // Guarda los campos NO nulos de `device` (null = no cambiar). Devuelve false
    // si el usuario no está registrado: el dispositivo se guarda junto al
    // perfil, y el perfil no se crea desde acá.
    boolean save(Recipient recipient, Device device);
}
