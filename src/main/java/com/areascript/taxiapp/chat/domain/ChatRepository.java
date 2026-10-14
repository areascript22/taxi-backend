package com.areascript.taxiapp.chat.domain;

// Puerto de salida: dónde viven los hilos de chat (hoy Firestore, colección
// chats/{rideId}/messages). Un fallo de almacenamiento se traduce a
// ExternalServiceException (common.error).
public interface ChatRepository {

    // Habilita el chat de una carrera: crea el hilo que la regla de seguridad
    // de Firestore usa para autorizar a los dos participantes a leer sus
    // mensajes. La clave es el rideId y no el passengerId, porque un mismo
    // pasajero tiene un rideId distinto por cada carrera y usar passengerId
    // mezclaría el chat de una carrera vieja con el de la nueva.
    void createThread(String rideId, String passengerId, String driverId);

    void addMessage(ChatMessage message);
}
