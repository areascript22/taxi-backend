package com.areascript.taxiapp.dispatch.application;

import com.areascript.taxiapp.common.error.ConflictException;
import com.areascript.taxiapp.common.error.ForbiddenException;
import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.dispatch.domain.DriverPresence;
import com.areascript.taxiapp.dispatch.domain.PresenceState;
import com.areascript.taxiapp.ride.domain.RideOutcome;
import com.areascript.taxiapp.ride.domain.RideRepository;
import com.areascript.taxiapp.ride.domain.RideTransitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Recibe la posición del conductor y decide a dónde va. UN solo endpoint para
// las dos situaciones, así la app no tiene que saber ni cambiar de destino:
//
//   sin carrera -> Postgres (driver_locations): es lo que usa el despacho para
//                  encontrar al conductor más cercano a un pedido.
//   en carrera  -> el nodo de la carrera en Realtime Database: es lo que lee el
//                  mapa del pasajero. NO toca Postgres: mientras está ocupado no
//                  recibe pedidos, y no hay razón para escribir una fila que nadie
//                  va a leer.
@Service
public class DriverLocationService {

    private static final Logger log = LoggerFactory.getLogger(DriverLocationService.class);

    private final DriverPresence presence;
    private final RideRepository rides;

    public DriverLocationService(DriverPresence presence, RideRepository rides) {
        this.presence = presence;
        this.rides = rides;
    }

    // `passengerId` es la carrera en la que va el conductor, o null si no tiene
    // ninguna. Lo sabe la app (es la pantalla del viaje) y se verifica acá: no
    // se confía en él.
    public void report(String driverUid, double latitude, double longitude, String passengerId) {
        validate(latitude, longitude);

        if (passengerId != null && !passengerId.isBlank()) {
            reportDuringRide(driverUid, latitude, longitude, passengerId);
            return;
        }
        reportWhileIdle(driverUid, latitude, longitude);
    }

    private void reportDuringRide(String driverUid, double latitude, double longitude, String passengerId) {
        RideOutcome outcome = rides.transact(
                passengerId,
                ride -> RideTransitions.updateDriverLocation(ride, driverUid, latitude, longitude));

        if (outcome.ride() == null) {
            throw new NotFoundException("No existe una carrera activa para passengerId: " + passengerId);
        }
        if (outcome.rejectedAsForbidden()) {
            throw new ForbiddenException("No eres el conductor de esa carrera");
        }
        if (!outcome.committed()) {
            throw new ConflictException("La carrera de passengerId=" + passengerId + " ya no está en curso");
        }
    }

    private void reportWhileIdle(String driverUid, double latitude, double longitude) {
        // El camino normal: una sola sentencia que guarda solo si está en línea y
        // libre.
        if (presence.recordLocationIfAvailable(driverUid, latitude, longitude)) {
            return;
        }

        // No se guardó: ahora sí se pregunta por qué, para dar una respuesta útil.
        PresenceState state = presence.state(driverUid).orElseThrow(() ->
                new NotFoundException("Todavía no tienes un perfil de conductor listo para recibir pedidos"));

        if (!state.online()) {
            throw new ConflictException("No estás en línea");
        }

        // Está en línea pero figura en carrera y no mandó cuál. O de verdad va en
        // una (la app debe indicarla) o el marcador quedó colgado: se confirma una
        // sola vez y, si estaba colgado, se corrige y se reintenta.
        if (rides.findInProgressOf(driverUid).isPresent()) {
            throw new ConflictException("Tienes una carrera en curso: indica passengerId para reportar tu ubicación");
        }
        log.warn("DispatchDebug | driverUid={} figuraba en carrera sin tener ninguna: se corrige", driverUid);
        presence.setOnRide(driverUid, false);
        if (!presence.recordLocationIfAvailable(driverUid, latitude, longitude)) {
            throw new ConflictException("No se pudo registrar la ubicación");
        }
    }

    private static void validate(double latitude, double longitude) {
        boolean validLatitude = !Double.isNaN(latitude) && latitude >= -90 && latitude <= 90;
        boolean validLongitude = !Double.isNaN(longitude) && longitude >= -180 && longitude <= 180;
        if (!validLatitude || !validLongitude) {
            throw new InvalidInputException("Ubicación inválida");
        }
    }
}
