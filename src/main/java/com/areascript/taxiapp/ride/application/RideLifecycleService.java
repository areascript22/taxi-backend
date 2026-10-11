package com.areascript.taxiapp.ride.application;

import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.common.error.ConflictException;
import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.error.ForbiddenException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.Driver;
import com.areascript.taxiapp.driveradmin.domain.DriverRepository;
import com.areascript.taxiapp.driveradmin.domain.Vehicle;
import com.areascript.taxiapp.notification.domain.PushMessage;
import com.areascript.taxiapp.notification.domain.PushRoutes;
import com.areascript.taxiapp.notification.domain.Recipient;
import com.areascript.taxiapp.notification.domain.UserNotifier;
import com.areascript.taxiapp.ride.domain.DriverContact;
import com.areascript.taxiapp.ride.domain.NewRide;
import com.areascript.taxiapp.ride.domain.PickupLocation;
import com.areascript.taxiapp.ride.domain.Ride;
import com.areascript.taxiapp.ride.domain.RideAccepted;
import com.areascript.taxiapp.ride.domain.RideEnded;
import com.areascript.taxiapp.ride.domain.RideOutcome;
import com.areascript.taxiapp.ride.domain.RideRepository;
import com.areascript.taxiapp.ride.domain.RideStatus;
import com.areascript.taxiapp.ride.domain.RideTimers;
import com.areascript.taxiapp.ride.domain.RideTransitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;

// Casos de uso que cambian una carrera: pedir, aceptar, cancelar y finalizar.
// Las reglas de qué cambio procede viven en RideTransitions; acá se
// coordinan con los demás (quién puede aceptar, a quién se avisa, qué
// reacciona al cambio) y se traduce el resultado a errores de negocio.
@Service
public class RideLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(RideLifecycleService.class);

    private final RideRepository rides;
    private final DriverRepository drivers;
    private final UserNotifier notifier;
    private final ApplicationEventPublisher events;
    private final RideTimers timers;
    private final TaxiappProperties.Rides settings;
    private final Clock clock;

    public RideLifecycleService(
            RideRepository rides,
            DriverRepository drivers,
            UserNotifier notifier,
            ApplicationEventPublisher events,
            RideTimers timers,
            TaxiappProperties properties,
            Clock clock
    ) {
        this.rides = rides;
        this.drivers = drivers;
        this.notifier = notifier;
        this.events = events;
        this.timers = timers;
        this.settings = properties.rides();
        this.clock = clock;
    }

    // Reemplaza el .set() que antes hacía passenger_app directo sobre Realtime
    // Database: se mueve acá para poder agendar la auto-cancelación justo donde
    // se crea la solicitud, y para que el nombre/foto del pasajero salgan del
    // token verificado en vez de confiar en lo que mande el cliente.
    public void requestRide(
            String passengerId,
            String passengerName,
            String passengerPhotoUrl,
            double pickupLatitude,
            double pickupLongitude,
            String pickupAddress,
            String pickupSector
    ) {
        PickupLocation pickup =
                PickupLocation.sanitized(pickupLatitude, pickupLongitude, pickupAddress, pickupSector);
        String rideId = passengerId + "_" + clock.millis();

        NewRide newRide = new NewRide(rideId, passengerId, passengerName, passengerPhotoUrl, pickup);
        RideOutcome outcome = rides.transact(passengerId, ride -> RideTransitions.request(ride, newRide));

        // Un viaje en curso no se pisa: pedir otro taxi le borraría al
        // pasajero el conductor que ya lo está esperando.
        if (!outcome.committed()) {
            throw new ConflictException("El pasajero passengerId=" + passengerId + " ya tiene un viaje en curso");
        }
        log.info("RideDebug | Solicitud creada para passengerId={}", passengerId);

        // Margen que le damos al cliente para cancelar por su cuenta (el
        // diálogo "Buscando conductor" de passenger_app usa 30 s) antes de que
        // el backend intervenga: cubre el caso de que el pasajero cierre la
        // app y nunca la vuelva a abrir, donde de otra forma la solicitud
        // quedaría 'pending' para siempre.
        //
        // La tarea lleva el rideId de ESTA solicitud: si el pasajero cancela y
        // vuelve a pedir dentro de la ventana, la tarea vieja no puede vencer
        // a la nueva.
        timers.expireAfter(settings.pendingRequestExpiry(), () -> expireIfStillPending(passengerId, rideId));
    }

    // Tarea agendada desde requestRide: si pasado el margen la solicitud sigue
    // 'pending' (nadie la aceptó y el pasajero tampoco la canceló), el backend
    // la cancela. Usa la misma transacción que accept/cancel para no pisar a
    // un conductor que la acepte justo en este instante.
    //
    // Corre en un hilo del scheduler: nunca relanza.
    private void expireIfStillPending(String passengerId, String rideId) {
        RideOutcome outcome;
        try {
            outcome = rides.transact(passengerId, ride -> RideTransitions.expire(ride, rideId));
        } catch (ExternalServiceException e) {
            log.error("RideDebug | No se pudo auto-cancelar la solicitud de passengerId={}: {}", passengerId, e.getMessage());
            return;
        }

        boolean actuallyExpired = outcome.committed()
                && outcome.ride() != null
                && outcome.ride().status() == RideStatus.CANCELLED;
        if (!actuallyExpired) {
            // No hizo falta: ya tenía conductor asignado, ya la habían
            // cancelado, o el nodo ya no existía. No es un error.
            return;
        }

        log.info("RideDebug | Solicitud de passengerId={} auto-cancelada tras {}s sin conductor",
                passengerId, settings.pendingRequestExpiry().toSeconds());
        scheduleCleanup(passengerId);
    }

    // Reemplaza la transacción que antes corría en driver_app directo sobre
    // Realtime Database: la asignación atómica del conductor vive acá para que,
    // si dos conductores aceptan a la vez, gane uno solo sin importar cuál app
    // llegó primero.
    public void acceptRide(
            String passengerId,
            String driverUid,
            String driverEmail,
            String driverDisplayName,
            String driverPhotoUrl,
            double driverLatitude,
            double driverLongitude
    ) {
        // Primero se autoriza al conductor, antes de tocar la carrera.
        DriverContact contact = authorizedContact(driverUid, driverEmail, driverDisplayName, driverPhotoUrl);

        RideOutcome outcome = rides.transact(
                passengerId,
                ride -> RideTransitions.assign(ride, contact, driverLatitude, driverLongitude));

        Ride ride = outcome.ride();
        if (ride == null) {
            // Cubre tanto "nunca existió" como el commit sin cambios sobre una
            // carrera ausente.
            throw rideNotFound(passengerId);
        }

        boolean assignedToThisDriver = ride.status() == RideStatus.DRIVER_ASSIGNED
                && driverUid.equals(ride.driverId());
        if (!outcome.committed() || !assignedToThisDriver) {
            throw new ConflictException("La carrera de passengerId=" + passengerId
                    + " ya fue tomada por otro conductor o ya no está disponible");
        }

        log.info("RideDebug | Carrera de passengerId={} aceptada por driverUid={}", passengerId, driverUid);
        notifier.notifyUser(
                Recipient.passenger(passengerId),
                PushMessage.translated(
                        "push.ride.accepted.title",
                        "push.ride.accepted.body",
                        PushRoutes.PASSENGER_RIDE_TRACKING
                )
        );

        // Quien reacciona a que la carrera fue aceptada (hoy, el chat abre su
        // hilo) se entera por el evento; los listeners no pueden revertirla.
        events.publishEvent(new RideAccepted(ride.rideId(), passengerId, driverUid));
    }

    // Quién puede aceptar carreras: un conductor registrado (existe su
    // perfil), aprobado y no bloqueado. Antes bastaba con tener un token
    // válido: un pasajero, o un conductor bloqueado o pendiente de
    // aprobación, podía llamar al endpoint y quedarse con la carrera.
    //
    // Falla cerrado: si el perfil no se puede leer NO se asigna (500), porque
    // no se puede comprobar que el conductor esté habilitado.
    private DriverContact authorizedContact(
            String driverUid,
            String driverEmail,
            String driverDisplayName,
            String driverPhotoUrl
    ) {
        Driver driver = drivers.findById(driverUid).orElseThrow(() -> {
            log.warn("RideDebug | uid={} intentó aceptar una carrera sin ser un conductor registrado", driverUid);
            return new ForbiddenException("Solo un conductor registrado puede aceptar carreras");
        });

        // Un conductor anterior al sistema de aprobación (sin approvalStatus)
        // ya viene como APPROVED desde el repositorio.
        if (driver.approvalStatus() != ApprovalStatus.APPROVED) {
            log.warn("RideDebug | driverUid={} intentó aceptar una carrera con approvalStatus={}",
                    driverUid, driver.approvalStatus().wireValue());
            throw new ForbiddenException("El conductor no está aprobado para aceptar carreras");
        }
        if (driver.blocked()) {
            log.warn("RideDebug | driverUid={} está bloqueado e intentó aceptar una carrera", driverUid);
            throw new ForbiddenException("El conductor está bloqueado y no puede aceptar carreras");
        }

        // Teléfono y vehículo son información complementaria para el pasajero:
        // si todavía no los cargó, la asignación procede igual con null.
        Vehicle vehicle = driver.vehicle();
        return new DriverContact(
                driverUid,
                driverEmail,
                driverDisplayName,
                driverPhotoUrl,
                blankToNull(driver.phoneNumber()),
                vehicle == null ? null : blankToNull(vehicle.plate()),
                vehicle == null ? null : blankToNull(vehicle.brand()),
                vehicle == null ? null : blankToNull(vehicle.model()),
                vehicle == null ? null : blankToNull(vehicle.color())
        );
    }

    // Único camino para cancelar, usado tanto por el pasajero como por el
    // conductor: el rol de quien cancela se deriva del uid del token (si
    // coincide con passengerId es el pasajero, si coincide con el conductor ya
    // asignado es el conductor) y se avisa por push a la otra parte, algo que
    // solo el backend puede hacer.
    public void cancelRide(String passengerId, String callerUid) {
        RideOutcome outcome = rides.transact(passengerId, ride -> RideTransitions.cancel(ride, callerUid));

        // Cancelar es idempotente por intención: si la solicitud ya no existe
        // (el backend ya la limpió tras auto-expirarla, o tras una cancelación
        // previa) o ya está 'cancelled' (la auto-canceló el timeout, o llegó
        // una segunda llamada casi al mismo tiempo), el estado deseado -que la
        // carrera no siga activa- ya se cumplió. Se toma como éxito para que
        // el cliente no reciba un falso failure cuando en realidad ganó la
        // carrera contra el vencimiento.
        Ride ride = outcome.ride();
        if (ride == null) {
            log.info("RideDebug | cancelRide de passengerId={} no encontró la solicitud (ya limpiada) -- se toma como éxito idempotente", passengerId);
            return;
        }

        if (outcome.rejectedAsForbidden()) {
            throw rideForbidden(passengerId);
        }

        if (!outcome.committed() || ride.status() != RideStatus.CANCELLED) {
            if (ride.status() == RideStatus.CANCELLED) {
                log.info("RideDebug | cancelRide de passengerId={} encontró la solicitud ya cancelada -- se toma como éxito idempotente", passengerId);
                return;
            }
            // Cualquier otro estado ya-terminal (ej. tripCompleted) sí es un
            // conflicto real: no tiene sentido "cancelar" un viaje que ya se
            // completó.
            throw rideAlreadyFinished(passengerId);
        }

        log.info("RideDebug | Carrera de passengerId={} cancelada por callerUid={} (rol={})",
                passengerId, callerUid, ride.cancelledBy());

        if (RideTransitions.CANCELLED_BY_PASSENGER.equals(ride.cancelledBy())) {
            if (ride.driverId() != null) {
                notifier.notifyUser(
                        Recipient.driver(ride.driverId()),
                        PushMessage.translated(
                                "push.ride.cancelledByPassenger.title",
                                "push.ride.cancelledByPassenger.body",
                                PushRoutes.DRIVER_BOOKING
                        )
                );
            }
        } else {
            notifier.notifyUser(
                    Recipient.passenger(passengerId),
                    PushMessage.translated(
                            "push.ride.cancelledByDriver.title",
                            "push.ride.cancelledByDriver.body",
                            PushRoutes.PASSENGER_RIDE_TRACKING
                    )
            );
        }

        scheduleCleanup(passengerId);

        // Si ya habia conductor, queda libre: quien reacciona (el despacho) se
        // entera por el evento.
        if (ride.driverId() != null) {
            events.publishEvent(new RideEnded(ride.rideId(), passengerId, ride.driverId()));
        }
    }

    // El conductor asignado avisa que llegó al punto de recogida. Antes la app
    // escribía el estado directo en Realtime Database, sin que nadie
    // verificara quién era ni en qué estado estaba la carrera.
    //
    // Es idempotente: si ya figura como llegado, es un éxito (la app reintenta
    // ante un timeout).
    public void markDriverArrived(String passengerId, String driverUid) {
        RideOutcome outcome = rides.transact(passengerId, ride -> RideTransitions.markArrived(ride, driverUid));
        requireApplied(outcome, passengerId, RideStatus.DRIVER_ARRIVED);
        log.info("RideDebug | Conductor driverUid={} llegó al punto de recogida de passengerId={}", driverUid, passengerId);
    }

    // El pasajero confirma que va "en camino" hacia el vehículo: el viaje
    // empieza. Solo el pasajero dueño de la carrera, y solo con el conductor
    // ya llegado. Idempotente, igual que markDriverArrived.
    public void startTrip(String passengerId, String passengerUid) {
        RideOutcome outcome = rides.transact(passengerId, ride -> RideTransitions.startTrip(ride, passengerUid));
        requireApplied(outcome, passengerId, RideStatus.TRIP_STARTED);
        log.info("RideDebug | Viaje de passengerId={} iniciado", passengerId);
    }

    // Traduce el resultado de una transición a "se aplicó" o al error de
    // negocio que corresponda (404 / 403 / 409).
    private void requireApplied(RideOutcome outcome, String passengerId, RideStatus expected) {
        Ride ride = outcome.ride();
        if (ride == null) {
            throw rideNotFound(passengerId);
        }
        if (outcome.rejectedAsForbidden()) {
            throw rideForbidden(passengerId);
        }
        if (!outcome.committed() || ride.status() != expected) {
            throw new ConflictException("La carrera de passengerId=" + passengerId
                    + " no está en un estado que admita esta operación");
        }
    }

    // Solo el conductor asignado puede finalizar el viaje (verificado en el
    // servidor, no confiando en el cliente). La limpieza posterior corre en el
    // backend, así que no depende de que la app siga abierta.
    public void completeTrip(String passengerId, String driverUid) {
        RideOutcome outcome = rides.transact(passengerId, ride -> RideTransitions.complete(ride, driverUid));

        Ride ride = outcome.ride();
        if (ride == null) {
            throw rideNotFound(passengerId);
        }
        if (outcome.rejectedAsForbidden()) {
            throw rideForbidden(passengerId);
        }
        if (!outcome.committed() || ride.status() != RideStatus.TRIP_COMPLETED) {
            throw rideAlreadyFinished(passengerId);
        }

        log.info("RideDebug | Viaje de passengerId={} finalizado por driverUid={}", passengerId, driverUid);
        scheduleCleanup(passengerId);

        events.publishEvent(new RideEnded(ride.rideId(), passengerId, driverUid));
    }

    // Borra el nodo unos segundos después de llegar a un estado terminal, una
    // vez que ambos clientes ya tuvieron tiempo de recibirlo por su listener.
    // Solo borra si el estado sigue siendo terminal (ver
    // RideTransitions.removeIfTerminal).
    private void scheduleCleanup(String passengerId) {
        timers.cleanupAfter(settings.terminalCleanupDelay(), () -> {
            try {
                rides.transact(passengerId, RideTransitions::removeIfTerminal);
            } catch (ExternalServiceException e) {
                log.error("RideDebug | No se pudo limpiar el nodo de passengerId={} tras finalizar/cancelar: {}",
                        passengerId, e.getMessage());
            }
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static NotFoundException rideNotFound(String passengerId) {
        return new NotFoundException("No existe una solicitud de carrera activa para passengerId: " + passengerId);
    }

    private static ConflictException rideAlreadyFinished(String passengerId) {
        return new ConflictException("La carrera de passengerId=" + passengerId + " ya estaba cancelada o finalizada");
    }

    private static ForbiddenException rideForbidden(String passengerId) {
        return new ForbiddenException("El llamador no está autorizado para esta operación sobre passengerId=" + passengerId);
    }
}
