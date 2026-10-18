package com.areascript.taxiapp.notification.domain;

// Pantalla a la que lleva un push al tocarlo. Son rutas registradas en cada
// app (ver PushNotificationsService) y tienen que apuntar a una pantalla que
// no requiera un objeto `extra`, porque el payload de un push no puede llevar
// objetos Dart.
public final class PushRoutes {

    // passenger_app: seguimiento del viaje.
    public static final String PASSENGER_RIDE_TRACKING = "/ride_tracking";

    // driver_app: pantalla de carreras.
    public static final String DRIVER_BOOKING = "/booking";

    private PushRoutes() {
    }
}
