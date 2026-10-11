package com.areascript.taxiapp.ride.domain;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.areascript.taxiapp.ride.domain.RideChange.Reason.FORBIDDEN;
import static com.areascript.taxiapp.ride.domain.RideChange.Reason.NOT_ALLOWED;

// Las reglas de cambio de estado de una carrera, como funciones puras del
// estado actual (ver RideTransition). Antes vivían dentro de los
// Transaction.Handler de Firebase, mezcladas con el SDK; ahora son lo único
// que hay que leer para saber qué puede pasarle a una carrera, y se prueban
// sin Firebase.
//
// Toda regla devuelve Unchanged si la carrera no existe (current == null): el
// repositorio confirma sin cambios y quien llamó detecta la ausencia. No se
// rechaza acá porque con una referencia "fría" el SDK puede entregar null en
// una primera pasada aunque el nodo exista, y abortar cancelaría la
// transacción sin darle chance de reintentar con los datos reales.
public final class RideTransitions {

    // Quién figura como autor de una cancelación hecha por el sistema.
    public static final String CANCELLED_BY_SYSTEM = "system";
    public static final String CANCELLED_BY_PASSENGER = "passenger";
    public static final String CANCELLED_BY_DRIVER = "driver";

    private RideTransitions() {
    }

    // PENDING -> DRIVER_ASSIGNED. Solo si sigue PENDING, para que si dos
    // conductores aceptan a la vez gane uno solo.
    public static RideChange assign(Ride current, DriverContact driver, double driverLat, double driverLng) {
        if (current == null) {
            return RideChange.unchanged();
        }
        if (current.status() != RideStatus.PENDING) {
            return RideChange.rejected(NOT_ALLOWED);
        }

        Map<String, Object> driverData = new HashMap<>();
        driverData.put("id", driver.id());
        driverData.put("email", driver.email());
        driverData.put("displayName", driver.displayName());
        driverData.put("photoUrl", driver.photoUrl());
        driverData.put("phoneNumber", driver.phoneNumber());
        driverData.put("vehiclePlate", driver.vehiclePlate());
        driverData.put("vehicleBrand", driver.vehicleBrand());
        driverData.put("vehicleModel", driver.vehicleModel());
        driverData.put("vehicleColor", driver.vehicleColor());

        Map<String, Object> location = new HashMap<>();
        location.put("latitude", driverLat);
        location.put("longitude", driverLng);

        boolean knownPickup = current.pickupLatitude() != null && current.pickupLongitude() != null;
        Double initialDistance = knownPickup
                ? GeoDistance.metersBetween(driverLat, driverLng, current.pickupLatitude(), current.pickupLongitude())
                : null;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", RideStatus.DRIVER_ASSIGNED.wireValue());
        fields.put("updatedAt", ServerTime.NOW);
        fields.put("driver/data", driverData);
        fields.put("driver/location", location);
        fields.put("driver/initialDistance", initialDistance);
        return RideChange.update(fields);
    }

    // Cualquier estado no terminal -> CANCELLED, por el pasajero dueño de la
    // carrera o por el conductor ya asignado; nadie más.
    public static RideChange cancel(Ride current, String callerUid) {
        if (current == null) {
            return RideChange.unchanged();
        }
        if (current.isTerminal()) {
            return RideChange.rejected(NOT_ALLOWED);
        }

        String cancelledBy;
        if (callerUid.equals(current.passengerId())) {
            cancelledBy = CANCELLED_BY_PASSENGER;
        } else if (callerUid.equals(current.driverId())) {
            cancelledBy = CANCELLED_BY_DRIVER;
        } else {
            return RideChange.rejected(FORBIDDEN);
        }

        return RideChange.update(cancelledFields(cancelledBy));
    }

    // PENDING -> CANCELLED por el sistema: red de seguridad para el pasajero
    // que cerró la app sin cancelar. Si ya la aceptó un conductor, o ya la
    // canceló el pasajero, no hay nada que hacer.
    public static RideChange expire(Ride current) {
        if (current == null) {
            return RideChange.unchanged();
        }
        if (current.status() != RideStatus.PENDING) {
            return RideChange.rejected(NOT_ALLOWED);
        }
        return RideChange.update(cancelledFields(CANCELLED_BY_SYSTEM));
    }

    // TRIP_STARTED -> TRIP_COMPLETED, solo por el conductor asignado. El estado
    // se valida ANTES que la identidad: un tercero sobre un viaje no iniciado
    // recibe "no permitido", no "prohibido".
    //
    // Solo se puede finalizar un viaje que el pasajero ya confirmó que arrancó
    // (el conductor marca "he llegado", el pasajero confirma "en camino" y
    // recién ahí pasa a TRIP_STARTED). TripScreen solo muestra "Finalizar
    // viaje" en ese estado, pero igual se valida acá porque el cliente no es
    // fuente de verdad.
    public static RideChange complete(Ride current, String driverUid) {
        if (current == null) {
            return RideChange.unchanged();
        }
        if (current.status() != RideStatus.TRIP_STARTED) {
            return RideChange.rejected(NOT_ALLOWED);
        }
        if (!driverUid.equals(current.driverId())) {
            return RideChange.rejected(FORBIDDEN);
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", RideStatus.TRIP_COMPLETED.wireValue());
        fields.put("updatedAt", ServerTime.NOW);
        return RideChange.update(fields);
    }

    // Borra la carrera, pero solo si sigue en un estado terminal: no pisa una
    // solicitud nueva que el pasajero haya creado en la misma clave mientras
    // tanto.
    public static RideChange removeIfTerminal(Ride current) {
        if (current != null && current.isTerminal()) {
            return RideChange.remove();
        }
        return RideChange.unchanged();
    }

    private static Map<String, Object> cancelledFields(String cancelledBy) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", RideStatus.CANCELLED.wireValue());
        fields.put("cancelledBy", cancelledBy);
        fields.put("updatedAt", ServerTime.NOW);
        return fields;
    }
}
