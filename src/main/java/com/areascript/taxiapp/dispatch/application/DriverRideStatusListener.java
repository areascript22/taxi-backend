package com.areascript.taxiapp.dispatch.application;

import com.areascript.taxiapp.dispatch.domain.DriverPresence;
import com.areascript.taxiapp.ride.domain.RideAccepted;
import com.areascript.taxiapp.ride.domain.RideEnded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// Mantiene al día si el conductor está ocupado: la feature de carreras no sabe
// que existe un despacho, solo publica lo que pasó.
//
// Nunca relanza: la carrera ya quedó confirmada y esto no puede revertirla. Si
// falla, el marcador queda desactualizado y se corrige solo la próxima vez que
// el conductor se ponga en línea o reporte su ubicación (ver
// DriverAvailabilityService y DriverLocationService).
@Component
public class DriverRideStatusListener {

    private static final Logger log = LoggerFactory.getLogger(DriverRideStatusListener.class);

    private final DriverPresence presence;

    public DriverRideStatusListener(DriverPresence presence) {
        this.presence = presence;
    }

    @EventListener
    public void on(RideAccepted event) {
        mark(event.driverId(), true);
    }

    @EventListener
    public void on(RideEnded event) {
        mark(event.driverId(), false);
    }

    private void mark(String driverUid, boolean onRide) {
        try {
            presence.setOnRide(driverUid, onRide);
        } catch (RuntimeException e) {
            log.error("DispatchDebug | No se pudo marcar onRide={} para driverUid={}: {}",
                    onRide, driverUid, e.getMessage(), e);
        }
    }
}
