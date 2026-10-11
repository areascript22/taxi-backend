package com.areascript.taxiapp.dispatch.domain;

// Puerto de salida: la bandeja de ofertas de cada conductor. Cada conductor
// escucha SOLO la suya, así no ve los pedidos de quienes no le tocan.
//
// La bandeja es también la evidencia de qué conductores recibieron una oferta:
// `wasOffered` la consulta, así la verificación al aceptar no depende de la
// memoria del servidor (que se pierde al reiniciar).
//
// Un fallo del almacenamiento se traduce a ExternalServiceException
// (common.error).
public interface OfferInbox {

    // Deja la oferta en la bandeja del conductor (reemplaza una anterior de la
    // misma carrera).
    void offer(String driverUid, Offer offer);

    // Retira la oferta de esa carrera de la bandeja. No falla si no está.
    void withdraw(String driverUid, String passengerId);

    // ¿Tiene el conductor una oferta de ESA solicitud (rideId) en su bandeja?
    // El rideId evita que una oferta vieja del mismo pasajero autorice una
    // solicitud nueva.
    boolean wasOffered(String driverUid, String passengerId, String rideId);
}
