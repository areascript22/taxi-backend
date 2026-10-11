package com.areascript.taxiapp.dispatch.application;

import com.areascript.taxiapp.common.config.DispatchProperties;
import com.areascript.taxiapp.dispatch.domain.DispatchPlan;
import com.areascript.taxiapp.dispatch.domain.DispatchPlans;
import com.areascript.taxiapp.dispatch.domain.DispatchRing;
import com.areascript.taxiapp.dispatch.domain.DispatchTimers;
import com.areascript.taxiapp.dispatch.domain.DriverPresence;
import com.areascript.taxiapp.dispatch.domain.NearbyDriver;
import com.areascript.taxiapp.dispatch.domain.NoDriversAvailable;
import com.areascript.taxiapp.dispatch.domain.Offer;
import com.areascript.taxiapp.dispatch.domain.OfferInbox;
import com.areascript.taxiapp.ride.domain.Ride;
import com.areascript.taxiapp.ride.domain.RideAccepted;
import com.areascript.taxiapp.ride.domain.RideRepository;
import com.areascript.taxiapp.ride.domain.RideRequestWithdrawn;
import com.areascript.taxiapp.ride.domain.RideRequested;
import com.areascript.taxiapp.ride.domain.RideStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// El despacho por anillos: cuando un pasajero pide un taxi, ofrece el pedido a
// los conductores disponibles más cercanos y, si nadie lo acepta en el tiempo de
// ese anillo, amplía al siguiente (solo a los conductores nuevos).
//
//   anillo 1 (p. ej. 3 km): los N más cercanos -> esperar
//   anillo 2 (6 km): los N más cercanos que no estaban en el 1 -> esperar
//   ...
//   sin anillos -> NoDriversAvailable
//
// Reacciona a hechos de la feature de carreras (RideRequested, RideAccepted,
// RideRequestWithdrawn) y le devuelve uno (NoDriversAvailable): nunca modifica
// la carrera por su cuenta.
//
// Cada búsqueda es una SESIÓN en memoria, con una foto del plan al arrancar. La
// verdad de "a quién se le ofreció" es la bandeja de cada conductor (OfferInbox),
// no esta memoria: si el servidor se reinicia, la sesión se pierde pero las
// ofertas ya hechas siguen siendo válidas para aceptar.
@Component
public class RingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RingDispatcher.class);

    // Holgura sobre la duración total de la búsqueda: la oferta no debe vencer
    // justo cuando el último anillo termina.
    private static final Duration OFFER_MARGIN = Duration.ofSeconds(5);

    private final DispatchPlans plans;
    private final DriverPresence presence;
    private final OfferInbox inbox;
    private final DispatchTimers timers;
    private final RideRepository rides;
    private final ApplicationEventPublisher events;
    private final DispatchProperties properties;
    private final Clock clock;

    // Una búsqueda por pasajero (un pasajero tiene una sola solicitud).
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public RingDispatcher(
            DispatchPlans plans,
            DriverPresence presence,
            OfferInbox inbox,
            DispatchTimers timers,
            RideRepository rides,
            ApplicationEventPublisher events,
            DispatchProperties properties,
            Clock clock
    ) {
        this.plans = plans;
        this.presence = presence;
        this.inbox = inbox;
        this.timers = timers;
        this.rides = rides;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    // ---- Hechos que llegan de la feature de carreras ----

    // No relanza: la solicitud ya está creada y esto no puede revertirla. Si el
    // despacho falla, el pedido sigue visible como hasta ahora y vence normal.
    @EventListener
    public void on(RideRequested event) {
        if (!properties.enabled()) {
            return;
        }
        try {
            DispatchPlan plan = plans.current();
            if (plan.isEmpty()) {
                log.warn("DispatchDebug | No hay anillos configurados: no se despacha rideId={}", event.rideId());
                return;
            }
            Session session = new Session(event, plan, clock.instant());
            Session replaced = sessions.put(event.passengerId(), session);
            if (replaced != null) {
                // Pidió de nuevo: la búsqueda anterior ya no vale.
                withdrawAll(replaced);
            }
            // Fuera del hilo del request: el pasajero no espera a que se busque.
            timers.after(Duration.ZERO, () -> runRing(session, 0));
        } catch (RuntimeException e) {
            log.error("DispatchDebug | No se pudo iniciar el despacho de rideId={}: {}",
                    event.rideId(), e.getMessage(), e);
        }
    }

    @EventListener
    public void on(RideAccepted event) {
        finish(event.passengerId(), event.rideId());
    }

    @EventListener
    public void on(RideRequestWithdrawn event) {
        finish(event.passengerId(), event.rideId());
    }

    // ---- La búsqueda ----

    // Corre en un hilo del planificador: nunca relanza.
    private void runRing(Session session, int index) {
        try {
            // ¿Sigue siendo esta la búsqueda vigente? (si el pasajero canceló o
            // alguien aceptó, la sesión ya no está)
            if (sessions.get(session.passengerId()) != session) {
                return;
            }
            if (index >= session.plan().rings().size()) {
                exhausted(session);
                return;
            }
            if (!stillPending(session)) {
                finish(session.passengerId(), session.rideId());
                return;
            }

            DispatchRing ring = session.plan().rings().get(index);
            List<NearbyDriver> nearby = presence.findAvailableNear(
                    session.pickupLatitude(),
                    session.pickupLongitude(),
                    ring.radiusMeters(),
                    session.plan().locationMaxAge(),
                    ring.maxDrivers(),
                    session.offered());

            Instant expiresAt = session.startedAt().plus(session.plan().totalWait()).plus(OFFER_MARGIN);
            for (NearbyDriver driver : nearby) {
                offerTo(session, driver, ring, expiresAt);
            }
            log.info("DispatchDebug | rideId={} anillo {} ({} m): {} conductores nuevos, {} en total",
                    session.rideId(), ring.number(), ring.radiusMeters(), nearby.size(), session.offered().size());

            timers.after(ring.waitTime(), () -> runRing(session, index + 1));
        } catch (RuntimeException e) {
            log.error("DispatchDebug | Falló el anillo {} de rideId={}: {}", index + 1, session.rideId(), e.getMessage(), e);
        }
    }

    private void offerTo(Session session, NearbyDriver driver, DispatchRing ring, Instant expiresAt) {
        Offer offer = new Offer(
                session.rideId(),
                session.passengerId(),
                session.passengerName(),
                session.pickupLatitude(),
                session.pickupLongitude(),
                session.pickupAddress(),
                session.pickupSector(),
                ring.number(),
                driver.distanceMeters(),
                expiresAt);
        try {
            inbox.offer(driver.driverUid(), offer);
            // Solo se anota a quien SÍ recibió la oferta: si falló, el siguiente
            // anillo puede volver a intentarlo con él.
            session.offered().add(driver.driverUid());
        } catch (RuntimeException e) {
            log.error("DispatchDebug | No se pudo ofrecer rideId={} a driverUid={}: {}",
                    session.rideId(), driver.driverUid(), e.getMessage());
        }
    }

    private boolean stillPending(Session session) {
        Ride ride = rides.find(session.passengerId()).orElse(null);
        return ride != null
                && session.rideId().equals(ride.rideId())
                && ride.status() == RideStatus.PENDING;
    }

    private void exhausted(Session session) {
        log.info("DispatchDebug | rideId={}: se recorrieron los {} anillos sin conductor",
                session.rideId(), session.plan().rings().size());
        sessions.remove(session.passengerId(), session);
        withdrawAll(session);

        // Solo cuando el despacho es la única vía (ver DispatchProperties). Si
        // no, la solicitud sigue su curso y vence por su cuenta.
        if (properties.enforceOffers()) {
            events.publishEvent(new NoDriversAvailable(session.rideId(), session.passengerId()));
        }
    }

    // Termina la búsqueda de ESTA solicitud y retira lo que ofreció. Si hay una
    // búsqueda más nueva del mismo pasajero (otra solicitud), no la toca.
    private void finish(String passengerId, String rideId) {
        Session session = sessions.get(passengerId);
        if (session == null || !session.rideId().equals(rideId)) {
            return;
        }
        sessions.remove(passengerId, session);
        withdrawAll(session);
    }

    private void withdrawAll(Session session) {
        for (String driverUid : Set.copyOf(session.offered())) {
            try {
                inbox.withdraw(driverUid, session.passengerId());
            } catch (RuntimeException e) {
                // La oferta tiene vencimiento: si no se pudo retirar, deja de
                // mostrarse sola.
                log.error("DispatchDebug | No se pudo retirar la oferta de rideId={} a driverUid={}: {}",
                        session.rideId(), driverUid, e.getMessage());
            }
        }
    }

    // Cuántas búsquedas hay en curso (para tests y diagnóstico).
    int activeSessions() {
        return sessions.size();
    }

    // Una búsqueda en curso. `offered` es mutable y se toca desde el hilo del
    // planificador y desde el de los eventos, por eso un conjunto concurrente.
    private static final class Session {
        private final String rideId;
        private final String passengerId;
        private final String passengerName;
        private final double pickupLatitude;
        private final double pickupLongitude;
        private final String pickupAddress;
        private final String pickupSector;
        private final DispatchPlan plan;
        private final Instant startedAt;
        private final Set<String> offered = ConcurrentHashMap.newKeySet();

        Session(RideRequested event, DispatchPlan plan, Instant startedAt) {
            this.rideId = event.rideId();
            this.passengerId = event.passengerId();
            this.passengerName = event.passengerName();
            this.pickupLatitude = event.pickupLatitude();
            this.pickupLongitude = event.pickupLongitude();
            this.pickupAddress = event.pickupAddress();
            this.pickupSector = event.pickupSector();
            this.plan = plan;
            this.startedAt = startedAt;
        }

        String rideId() { return rideId; }
        String passengerId() { return passengerId; }
        String passengerName() { return passengerName; }
        double pickupLatitude() { return pickupLatitude; }
        double pickupLongitude() { return pickupLongitude; }
        String pickupAddress() { return pickupAddress; }
        String pickupSector() { return pickupSector; }
        DispatchPlan plan() { return plan; }
        Instant startedAt() { return startedAt; }
        Set<String> offered() { return offered; }
    }
}
