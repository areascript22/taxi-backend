package com.areascript.taxiapp.ride.domain;

// Marca "la hora del servidor de la base de datos" dentro de una actualización
// (RideChange.Update). El dominio no sabe cómo la representa cada base: el
// adaptador la traduce (en Realtime Database, ServerValue.TIMESTAMP).
public enum ServerTime {
    NOW
}
