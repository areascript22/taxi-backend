package com.areascript.taxiapp.notification.domain;

// Puerto de salida: cómo se entrega un push a un dispositivo. Hoy lo
// implementa FcmPushSender (Firebase Cloud Messaging); si mañana se cambia de
// proveedor, solo cambia la implementación.
public interface PushSender {

    // Entrega `content` al dispositivo identificado por `deviceToken`.
    //
    // Contrato: NO relanza. Un push que falla no debe tumbar el flujo que lo
    // dispara (una carrera ya aceptada no se revierte porque la notificación
    // no llegó), así que la implementación registra el error y vuelve.
    void send(String deviceToken, PushContent content);
}
