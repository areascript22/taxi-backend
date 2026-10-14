package com.areascript.taxiapp.ride.application;

import com.areascript.taxiapp.account.domain.ActiveRideLookup;
import com.areascript.taxiapp.chat.domain.ChatRide;
import com.areascript.taxiapp.chat.domain.ChatRideLookup;
import com.areascript.taxiapp.ride.domain.Ride;
import com.areascript.taxiapp.ride.domain.RideRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

// Consultas sobre carreras. Además de servir los endpoints de "viaje activo",
// es quien implementa los puertos que otras features necesitan saber de una
// carrera (cuenta: ¿tiene un viaje en curso?; chat: ¿puede escribir?).
@Service
public class RideQueryService implements ActiveRideLookup, ChatRideLookup {

    private final RideRepository rides;

    public RideQueryService(RideRepository rides) {
        this.rides = rides;
    }

    // Reemplaza la lectura directa que hacía passenger_app sobre su propio
    // nodo: mismo resultado, pero la identidad de "de quién es este viaje"
    // sale del token verificado (el caller solo puede consultar el suyo).
    //
    // Devuelve el nodo crudo, que RideEntity.fromJson de passenger_app parsea
    // igual que cuando venía del listener en vivo.
    public Optional<Map<String, Object>> findActiveRideForPassenger(String passengerId) {
        return rides.find(passengerId)
                .filter(Ride::isActiveForPassenger)
                .map(Ride::document);
    }

    // Reemplaza el escaneo completo de las solicitudes que hacía driver_app
    // client-side: descargaba al dispositivo del conductor la data de TODAS
    // las solicitudes activas (incluyendo pickup y nombre de pasajeros ajenos
    // a él) solo para filtrar localmente la suya. Acá el filtrado ocurre en el
    // servidor y solo se devuelve el viaje que le pertenece.
    public Optional<Map<String, Object>> findActiveRideForDriver(String driverUid) {
        return rides.findInProgressOf(driverUid).map(Ride::document);
    }

    // ActiveRideLookup: borrar la cuenta en medio de un viaje no está
    // permitido, ni como pasajero ni como conductor.
    @Override
    public boolean hasActiveRide(String uid) {
        return findActiveRideForDriver(uid).isPresent() || findActiveRideForPassenger(uid).isPresent();
    }

    // ChatRideLookup: el chat solo tiene sentido con un conductor asignado y
    // el viaje sin terminar. 'pending' no cuenta: no hay nadie del otro lado
    // para leerlo.
    @Override
    public Optional<ChatRide> findByPassenger(String passengerId) {
        return rides.find(passengerId)
                .map(ride -> new ChatRide(ride.rideId(), passengerId, ride.driverId(), ride.isInProgress()));
    }
}
