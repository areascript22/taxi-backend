package com.areascript.taxiapp.dispatch.application;

import com.areascript.taxiapp.common.error.ForbiddenException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.dispatch.domain.DriverPresence;
import com.areascript.taxiapp.dispatch.domain.PresenceState;
import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.Driver;
import com.areascript.taxiapp.driveradmin.domain.DriverRepository;
import com.areascript.taxiapp.ride.domain.RideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// El interruptor "estoy en línea" del conductor.
//
// Ponerse en línea exige estar habilitado (aprobado y no bloqueado). Eso se
// consulta donde hoy vive la verdad de la aprobación y el bloqueo, que sigue
// siendo Firestore (DriverRepository): Postgres todavía no las recibe. Mismo
// criterio que aceptar una carrera.
//
// Desconectarse nunca se rechaza: un conductor siempre puede dejar de recibir
// pedidos.
@Service
public class DriverAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(DriverAvailabilityService.class);

    private final DriverPresence presence;
    private final DriverRepository drivers;
    private final RideRepository rides;

    public DriverAvailabilityService(DriverPresence presence, DriverRepository drivers, RideRepository rides) {
        this.presence = presence;
        this.drivers = drivers;
        this.rides = rides;
    }

    public void goOnline(String driverUid) {
        Driver driver = drivers.findById(driverUid).orElseThrow(() ->
                new ForbiddenException("Solo un conductor registrado puede ponerse en línea"));
        if (driver.approvalStatus() != ApprovalStatus.APPROVED) {
            throw new ForbiddenException("El conductor no está aprobado para ponerse en línea");
        }
        if (driver.blocked()) {
            throw new ForbiddenException("El conductor está bloqueado y no puede ponerse en línea");
        }

        PresenceState before = presence.state(driverUid).orElseThrow(DriverAvailabilityService::notSynced);

        // Si quedó marcado "en carrera" pero ya no tiene ninguna (el evento de fin
        // se perdió, o el servidor se reinició a mitad), se corrige acá. Ponerse
        // en línea es poco frecuente, así que el costo de buscar la carrera real
        // (recorre las solicitudes activas) es aceptable.
        if (before.onRide() && rides.findInProgressOf(driverUid).isEmpty()) {
            log.warn("DispatchDebug | driverUid={} figuraba en carrera sin tener ninguna: se corrige", driverUid);
            presence.setOnRide(driverUid, false);
        }

        if (!presence.setOnline(driverUid, true)) {
            throw notSynced();
        }
        log.info("DispatchDebug | driverUid={} en línea", driverUid);
    }

    // Idempotente: desconectar a quien ya está desconectado, o a quien todavía no
    // tiene perfil sincronizado, no es un error.
    public void goOffline(String driverUid) {
        presence.setOnline(driverUid, false);
        log.info("DispatchDebug | driverUid={} fuera de línea", driverUid);
    }

    public PresenceState state(String driverUid) {
        return presence.state(driverUid).orElseThrow(DriverAvailabilityService::notSynced);
    }

    private static NotFoundException notSynced() {
        return new NotFoundException("Todavía no tienes un perfil de conductor listo para recibir pedidos");
    }
}
