package com.areascript.taxiapp.service;

import com.areascript.taxiapp.dto.PushNotificationDTO;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);
    private static final String TAXI_REQUESTS_PATH = "taxi_requests";
    private static final String PASSENGERS_COLLECTION = "passengers";
    private static final String DRIVERS_COLLECTION = "drivers";
    private static final String VEHICLES_COLLECTION = "vehicles";
    private static final double EARTH_RADIUS_METERS = 6371000.0;

    // Rutas de push registradas en cada app (ver PushNotificationsService):
    // deben apuntar a una pantalla que no requiera un objeto `extra`, porque
    // el payload de un push no puede llevar objetos Dart.
    private static final String PASSENGER_PUSH_ROUTE = "/ride_tracking";
    private static final String DRIVER_PUSH_ROUTE = "/booking";

    // Margen para que ambas apps -que escuchan status vía onValue sobre este
    // mismo nodo- alcancen a recibir y procesar el status terminal (y
    // driver_app/passenger_app puedan reaccionar: diálogo de cancelación o
    // navegación al finalizar) antes de que el nodo desaparezca. Solo
    // entonces se limpia de Realtime Database.
    private static final long RIDE_CLEANUP_DELAY_SECONDS = 10;
    private static final Set<String> TERMINAL_STATUSES = Set.of("cancelled", "tripCompleted");
    // Statuses que cuentan como "viaje en curso" para findActiveRideForDriver
    // -- 'pending' no aplica del lado del conductor (todavía no hay ninguno
    // asignado, nada que resumir) y los TERMINAL_STATUSES ya terminaron. Debe
    // mantenerse en sync con _activeTripStatuses (driver_app), que hacía este
    // mismo filtro client-side antes de que este chequeo se moviera acá.
    private static final Set<String> ACTIVE_RIDE_STATUSES =
            Set.of("driverAssigned", "driverArrived", "tripStarted");
    // Igual que ACTIVE_RIDE_STATUSES pero para findActiveRideForPassenger:
    // acá 'pending' sí cuenta como "viaje en curso" -- si el pasajero cierra
    // la app mientras todavía no hay conductor asignado, al reabrir debe
    // poder resumir el diálogo "Buscando conductor" en vez de perder la
    // solicitud (que sigue viva en Realtime Database y cualquier conductor
    // podría aceptar mientras tanto).
    private static final Set<String> PASSENGER_ACTIVE_STATUSES =
            Set.of("pending", "driverAssigned", "driverArrived", "tripStarted");
    // Margen que le damos al cliente para cancelar por su cuenta (el
    // WaitingForDriverDialog de passenger_app usa 30s) antes de que el
    // backend intervenga -- cubre el caso de que el pasajero cierre la app y
    // nunca la vuelva a abrir, donde de otra forma la solicitud quedaría
    // 'pending' en Realtime Database para siempre. Se agenda en requestRide
    // y se ejecuta en expireIfStillPending.
    private static final long PENDING_REQUEST_EXPIRY_SECONDS = 35;

    // Defensa de última línea contra Plus Codes (Open Location Code, ej.
    // "GX7Q+2X Choluteca, Honduras") en pickupAddress: passenger_app ya
    // filtra esto en el origen (GeocodingResultParser), pero este endpoint
    // no puede asumir que todo cliente que lo llame (versión vieja de la
    // app, un futuro panel admin, etc.) lo haga. Si un Plus Code igual llega
    // acá, se despoja antes de persistir -- driver_app ya trata
    // pickupLocation.address vacío como "Nueva carrera" en vez de leerlo.
    private static final Pattern PLUS_CODE_PREFIX = Pattern.compile(
            "^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}[,\\s]*",
            Pattern.CASE_INSENSITIVE
    );

    // Chat entre pasajero y conductor durante un viaje activo (ver
    // firebase/firestore.rules para el modelo de datos completo y por qué
    // se usa rideId -- no passengerId -- como clave del hilo).
    private static final String CHATS_COLLECTION = "chats";
    private static final String MESSAGES_SUBCOLLECTION = "messages";
    private static final int CHAT_MESSAGE_MAX_LENGTH = 1000;
    private static final long CHAT_RETENTION_DAYS = 7;

    private enum OperationAbortReason { FORBIDDEN, NOT_ALLOWED }

    private final FirebaseDatabase firebaseDatabase;
    private final Firestore firestore;
    private final NotificationService notificationService;
    private final TaskScheduler taskScheduler;

    public RideService(
            FirebaseDatabase firebaseDatabase,
            Firestore firestore,
            NotificationService notificationService,
            TaskScheduler taskScheduler
    ) {
        this.firebaseDatabase = firebaseDatabase;
        this.firestore = firestore;
        this.notificationService = notificationService;
        this.taskScheduler = taskScheduler;
    }

    // Reemplaza el .set() que antes hacía passenger_app directo sobre
    // Realtime Database: se mueve acá para poder agendar la auto-cancelación
    // (expireIfStillPending) justo donde se crea la solicitud, y para que el
    // nombre/foto del pasajero salgan del token verificado en vez de confiar
    // en lo que mande el cliente (mismo criterio que acceptRide con los
    // datos del conductor).
    public void requestRide(
            String passengerId,
            String passengerDisplayName,
            String passengerPhotoUrl,
            double pickupLatitude,
            double pickupLongitude,
            String pickupAddress
    ) {
        DatabaseReference rideRef = firebaseDatabase.getReference(TAXI_REQUESTS_PATH).child(passengerId);

        Map<String, Object> pickupLocation = new HashMap<>();
        pickupLocation.put("latitude", pickupLatitude);
        pickupLocation.put("longitude", pickupLongitude);
        pickupLocation.put("address", sanitizeAddress(pickupAddress));

        Map<String, Object> passenger = new HashMap<>();
        passenger.put("name", passengerDisplayName);
        passenger.put("profileImage", passengerPhotoUrl);

        // rideId mantiene el mismo formato que generaba antes el cliente
        // (passenger_app) -- driver_app lo sigue usando como key de
        // dedup/identificación en su lista de solicitudes entrantes
        // (IncomingRequestEntity.rideId).
        Map<String, Object> rideData = new HashMap<>();
        rideData.put("rideId", passengerId + "_" + System.currentTimeMillis());
        rideData.put("userId", passengerId);
        rideData.put("passenger", passenger);
        rideData.put("pickupLocation", pickupLocation);
        rideData.put("status", "pending");
        rideData.put("createdAt", ServerValue.TIMESTAMP);
        rideData.put("updatedAt", ServerValue.TIMESTAMP);

        CompletableFuture<Void> future = new CompletableFuture<>();
        rideRef.setValue(rideData, (error, ref) -> {
            if (error != null) {
                future.completeExceptionally(error.toException());
            } else {
                future.complete(null);
            }
        });

        try {
            future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido al crear la solicitud de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideRequestException("No se pudo crear la solicitud", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("RideDebug | Error al crear la solicitud de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideRequestException("No se pudo crear la solicitud", e);
        }

        log.info("RideDebug | Solicitud creada para passengerId={}", passengerId);

        taskScheduler.schedule(
                () -> expireIfStillPending(passengerId),
                Instant.now().plusSeconds(PENDING_REQUEST_EXPIRY_SECONDS)
        );
    }

    // Tarea agendada desde requestRide: si pasado PENDING_REQUEST_EXPIRY_SECONDS
    // la solicitud sigue 'pending' (nadie la aceptó y el pasajero tampoco la
    // canceló por su cuenta), el backend la cancela automáticamente -- red de
    // seguridad para cuando el pasajero cierra la app y nunca la reabre, caso
    // en el que ni su propio timeout local ni ninguna otra acción del cliente
    // van a limpiar la solicitud. Usa la misma transacción check-then-mutate
    // que acceptRide/cancelRide para no pisar a un conductor que la acepte
    // justo en este instante.
    private void expireIfStillPending(String passengerId) {
        DatabaseReference rideRef = firebaseDatabase.getReference(TAXI_REQUESTS_PATH).child(passengerId);
        CompletableFuture<RideTransactionOutcome> future = new CompletableFuture<>();

        rideRef.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                if (currentData.getValue() == null) {
                    return Transaction.success(currentData);
                }

                if (!"pending".equals(currentData.child("status").getValue())) {
                    // Ya la aceptó un conductor, o el pasajero ya la canceló
                    // por su cuenta -- nada que hacer.
                    return Transaction.abort();
                }

                currentData.child("status").setValue("cancelled");
                currentData.child("cancelledBy").setValue("system");
                currentData.child("updatedAt").setValue(ServerValue.TIMESTAMP);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                future.complete(new RideTransactionOutcome(error, committed, currentData));
            }
        });

        RideTransactionOutcome outcome;
        try {
            outcome = future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido al auto-cancelar la solicitud de passengerId={}: {}", passengerId, e.getMessage(), e);
            return;
        } catch (ExecutionException | TimeoutException e) {
            log.error("RideDebug | Error al auto-cancelar la solicitud de passengerId={}: {}", passengerId, e.getMessage(), e);
            return;
        }

        if (outcome.error() != null) {
            log.error("RideDebug | Firebase rechazó la auto-cancelación de passengerId={}: {}", passengerId, outcome.error().getMessage());
            return;
        }

        DataSnapshot snapshot = outcome.currentData();
        boolean actuallyExpired = outcome.committed()
                && snapshot != null
                && snapshot.exists()
                && "cancelled".equals(snapshot.child("status").getValue());

        if (!actuallyExpired) {
            // No hizo falta: ya tenía conductor asignado, ya la habían
            // cancelado, o el nodo ya no existía. No es un error.
            return;
        }

        log.info("RideDebug | Solicitud de passengerId={} auto-cancelada tras {}s sin conductor", passengerId, PENDING_REQUEST_EXPIRY_SECONDS);
        scheduleRideCleanup(rideRef, passengerId);
    }

    // Teléfono y datos del vehículo no vienen en el token de Firebase (solo
    // uid/email/name/picture), así que se buscan en Firestore antes de
    // asignar al conductor. Es información complementaria para el pasajero:
    // si Firestore falla o el conductor todavía no cargó su vehículo, se
    // continúa la asignación igual con esos campos en null.
    private record DriverVehicleInfo(
            String phoneNumber,
            String vehiclePlate,
            String vehicleBrand,
            String vehicleModel,
            String vehicleColor
    ) {
        private static final DriverVehicleInfo EMPTY =
                new DriverVehicleInfo(null, null, null, null, null);
    }

    private DriverVehicleInfo fetchDriverVehicleInfo(String driverUid) {
        try {
            DocumentSnapshot driverSnapshot =
                    firestore.collection(DRIVERS_COLLECTION).document(driverUid).get().get(15, TimeUnit.SECONDS);
            if (!driverSnapshot.exists() || driverSnapshot.getData() == null) {
                return DriverVehicleInfo.EMPTY;
            }

            String phoneNumber = driverSnapshot.getString("phoneNumber");
            String vehicleId = driverSnapshot.getString("vehicleId");
            if (vehicleId == null || vehicleId.isBlank()) {
                return new DriverVehicleInfo(phoneNumber, null, null, null, null);
            }

            DocumentSnapshot vehicleSnapshot =
                    firestore.collection(VEHICLES_COLLECTION).document(vehicleId).get().get(15, TimeUnit.SECONDS);
            if (!vehicleSnapshot.exists() || vehicleSnapshot.getData() == null) {
                return new DriverVehicleInfo(phoneNumber, null, null, null, null);
            }

            return new DriverVehicleInfo(
                    phoneNumber,
                    vehicleSnapshot.getString("plate"),
                    vehicleSnapshot.getString("brand"),
                    vehicleSnapshot.getString("model"),
                    vehicleSnapshot.getString("color")
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("RideDebug | Interrumpido al buscar teléfono/vehículo de driverUid={}: {}", driverUid, e.getMessage());
            return DriverVehicleInfo.EMPTY;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("RideDebug | No se pudo buscar teléfono/vehículo de driverUid={}: {}", driverUid, e.getMessage());
            return DriverVehicleInfo.EMPTY;
        }
    }

    // Reemplaza la transacción que antes corría en el driver_app directo
    // sobre Realtime Database: mueve la asignación atómica del driver acá
    // para que, si dos conductores presionan "aceptar" al mismo tiempo, solo
    // uno gane sin importar cuál app llegó primero al cliente.
    public void acceptRide(
            String passengerId,
            String driverUid,
            String driverEmail,
            String driverDisplayName,
            String driverPhotoUrl,
            double driverLatitude,
            double driverLongitude
    ) {
        DatabaseReference rideRef = firebaseDatabase.getReference(TAXI_REQUESTS_PATH).child(passengerId);
        CompletableFuture<RideTransactionOutcome> future = new CompletableFuture<>();
        DriverVehicleInfo vehicleInfo = fetchDriverVehicleInfo(driverUid);

        rideRef.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                if (currentData.getValue() == null) {
                    // El Admin SDK puede invocar este handler con datos aún no
                    // sincronizados en la primera pasada sobre una referencia
                    // "fría" (recién creada, sin listener previo), devolviendo
                    // null aunque el nodo sí exista en el servidor. Si
                    // abortamos acá, cancelamos la transacción sin darle
                    // chance al SDK de reintentar con el valor real. En vez de
                    // eso, dejamos pasar sin modificar nada: si el nodo existe
                    // de verdad, el hash no calzará con el servidor y el SDK
                    // reintenta automáticamente este mismo callback con los
                    // datos reales; si de verdad no existe, el commit no hará
                    // ningún cambio y lo detectamos después de la transacción.
                    return Transaction.success(currentData);
                }

                if (!"pending".equals(currentData.child("status").getValue())) {
                    return Transaction.abort();
                }

                currentData.child("status").setValue("driverAssigned");
                currentData.child("updatedAt").setValue(ServerValue.TIMESTAMP);

                Map<String, Object> driverData = new HashMap<>();
                driverData.put("id", driverUid);
                driverData.put("email", driverEmail);
                driverData.put("displayName", driverDisplayName);
                driverData.put("photoUrl", driverPhotoUrl);
                driverData.put("phoneNumber", vehicleInfo.phoneNumber());
                driverData.put("vehiclePlate", vehicleInfo.vehiclePlate());
                driverData.put("vehicleBrand", vehicleInfo.vehicleBrand());
                driverData.put("vehicleModel", vehicleInfo.vehicleModel());
                driverData.put("vehicleColor", vehicleInfo.vehicleColor());
                currentData.child("driver").child("data").setValue(driverData);

                Map<String, Object> location = new HashMap<>();
                location.put("latitude", driverLatitude);
                location.put("longitude", driverLongitude);
                currentData.child("driver").child("location").setValue(location);

                Double pickupLat = asDouble(currentData.child("pickupLocation").child("latitude").getValue());
                Double pickupLng = asDouble(currentData.child("pickupLocation").child("longitude").getValue());
                Double initialDistance = (pickupLat != null && pickupLng != null)
                        ? haversineMeters(driverLatitude, driverLongitude, pickupLat, pickupLng)
                        : null;
                currentData.child("driver").child("initialDistance").setValue(initialDistance);

                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                future.complete(new RideTransactionOutcome(error, committed, currentData));
            }
        });

        RideTransactionOutcome outcome;
        try {
            outcome = future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido al aceptar la carrera de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideAcceptException("No se pudo aceptar la carrera", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("RideDebug | Error al aceptar la carrera de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideAcceptException("No se pudo aceptar la carrera", e);
        }

        if (outcome.error() != null) {
            log.error("RideDebug | Firebase rechazó la transacción de passengerId={}: {}", passengerId, outcome.error().getMessage());
            throw new RideAcceptException("No se pudo aceptar la carrera", outcome.error().toException());
        }

        DataSnapshot snapshot = outcome.currentData();
        if (snapshot == null || !snapshot.exists()) {
            // Cubre tanto el caso real de "nunca existió" como el commit sin
            // cambios que hacemos cuando doTransaction ve currentData nulo.
            throw new RideNotFoundException(passengerId);
        }

        String finalStatus = (String) snapshot.child("status").getValue();
        String assignedDriverId = (String) snapshot.child("driver").child("data").child("id").getValue();
        boolean assignedToThisDriver = "driverAssigned".equals(finalStatus) && driverUid.equals(assignedDriverId);

        if (!outcome.committed() || !assignedToThisDriver) {
            throw new RideAlreadyAssignedException(passengerId);
        }

        log.info("RideDebug | Carrera de passengerId={} aceptada por driverUid={}", passengerId, driverUid);
        String rideId = (String) snapshot.child("rideId").getValue();
        notifyByFcm(
                PASSENGERS_COLLECTION,
                passengerId,
                new PushNotificationDTO(
                        "¡Carrera aceptada!",
                        "Un conductor va en camino a recogerte",
                        PASSENGER_PUSH_ROUTE,
                        null,
                        null
                )
        );

        createChatThread(rideId, passengerId, driverUid);
    }

    // Habilita el chat de esta carrera: crea el doc padre que
    // firestore.rules usa para autorizar a los 2 participantes a leer
    // chats/{rideId}/messages/**. No relanza si falla -- igual que un push
    // que falla, no debe revertir una carrera ya aceptada; en el peor caso
    // el chat queda no disponible para esa carrera puntual.
    private void createChatThread(String rideId, String passengerId, String driverUid) {
        Map<String, Object> chatData = new HashMap<>();
        chatData.put("passengerId", passengerId);
        chatData.put("driverId", driverUid);
        chatData.put("createdAt", Timestamp.now());

        try {
            firestore.collection(CHATS_COLLECTION).document(rideId).set(chatData).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("ChatDebug | Interrumpido al crear el hilo de chat de rideId={}: {}", rideId, e.getMessage(), e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("ChatDebug | Error al crear el hilo de chat de rideId={}: {}", rideId, e.getMessage(), e);
        }
    }

    // Reemplaza los `.update({'status': 'cancelled', ...})` que antes hacían
    // driver_app y passenger_app directo sobre Realtime Database: se mueve
    // acá para (a) verificar con el token de Firebase que quien cancela es
    // realmente el pasajero dueño de la carrera o el conductor ya asignado
    // -no un tercero-, y (b) poder avisarle a la otra parte por push, algo
    // que solo el backend puede hacer (el Admin SDK de FCM no está expuesto
    // al cliente).
    public void cancelRide(String passengerId, String callerUid) {
        DatabaseReference rideRef = firebaseDatabase.getReference(TAXI_REQUESTS_PATH).child(passengerId);
        CompletableFuture<RideTransactionOutcome> future = new CompletableFuture<>();
        AtomicReference<OperationAbortReason> abortReason = new AtomicReference<>();

        rideRef.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                if (currentData.getValue() == null) {
                    // Mismo caso de referencia "fría" que en acceptRide: no
                    // abortamos, dejamos que el SDK reintente con el valor
                    // real si el nodo sí existe.
                    return Transaction.success(currentData);
                }

                String status = (String) currentData.child("status").getValue();
                if (TERMINAL_STATUSES.contains(status)) {
                    abortReason.set(OperationAbortReason.NOT_ALLOWED);
                    return Transaction.abort();
                }

                String assignedDriverId = (String) currentData.child("driver").child("data").child("id").getValue();
                String cancelledBy;
                if (callerUid.equals(passengerId)) {
                    cancelledBy = "passenger";
                } else if (callerUid.equals(assignedDriverId)) {
                    cancelledBy = "driver";
                } else {
                    abortReason.set(OperationAbortReason.FORBIDDEN);
                    return Transaction.abort();
                }

                currentData.child("status").setValue("cancelled");
                currentData.child("cancelledBy").setValue(cancelledBy);
                currentData.child("updatedAt").setValue(ServerValue.TIMESTAMP);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                future.complete(new RideTransactionOutcome(error, committed, currentData));
            }
        });

        RideTransactionOutcome outcome;
        try {
            outcome = future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido al cancelar la carrera de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideCancelException("No se pudo cancelar la carrera", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("RideDebug | Error al cancelar la carrera de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideCancelException("No se pudo cancelar la carrera", e);
        }

        if (outcome.error() != null) {
            log.error("RideDebug | Firebase rechazó la transacción de cancelación de passengerId={}: {}", passengerId, outcome.error().getMessage());
            throw new RideCancelException("No se pudo cancelar la carrera", outcome.error().toException());
        }

        DataSnapshot snapshot = outcome.currentData();

        // Cancelar es idempotente por intención: si la solicitud ya no
        // existe (el backend ya la limpió tras auto-expirarla, o tras una
        // cancelación previa) o si ya está en status "cancelled" (el backend
        // la auto-canceló por timeout, o llegó una segunda llamada a este
        // mismo endpoint casi al mismo tiempo), el estado deseado -que la
        // carrera no siga activa- ya se cumplió. Tratamos ambos casos como
        // éxito en vez de error, para que el cliente no reciba un falso
        // failure cuando en realidad ganó la carrera contra
        // expireIfStillPending (ver PENDING_REQUEST_EXPIRY_SECONDS).
        if (snapshot == null || !snapshot.exists()) {
            log.info("RideDebug | cancelRide de passengerId={} no encontró la solicitud (ya limpiada) -- se toma como éxito idempotente", passengerId);
            return;
        }

        if (abortReason.get() == OperationAbortReason.FORBIDDEN) {
            throw new RideForbiddenException(passengerId);
        }

        String finalStatus = (String) snapshot.child("status").getValue();
        if (!outcome.committed() || !"cancelled".equals(finalStatus)) {
            if ("cancelled".equals(finalStatus)) {
                log.info("RideDebug | cancelRide de passengerId={} encontró la solicitud ya cancelada -- se toma como éxito idempotente", passengerId);
                return;
            }
            // Cualquier otro status ya-terminal (ej. tripCompleted) sí es un
            // conflicto real: no tiene sentido "cancelar" un viaje que ya se
            // completó.
            throw new RideAlreadyFinishedException(passengerId);
        }

        String cancelledBy = (String) snapshot.child("cancelledBy").getValue();
        log.info("RideDebug | Carrera de passengerId={} cancelada por callerUid={} (rol={})", passengerId, callerUid, cancelledBy);

        if ("passenger".equals(cancelledBy)) {
            String assignedDriverId = (String) snapshot.child("driver").child("data").child("id").getValue();
            if (assignedDriverId != null) {
                notifyByFcm(
                        DRIVERS_COLLECTION,
                        assignedDriverId,
                        new PushNotificationDTO(
                                "Carrera cancelada",
                                "El pasajero canceló la carrera",
                                DRIVER_PUSH_ROUTE,
                                null,
                                null
                        )
                );
            }
        } else {
            notifyByFcm(
                    PASSENGERS_COLLECTION,
                    passengerId,
                    new PushNotificationDTO(
                            "Viaje cancelado",
                            "Tu conductor canceló el viaje",
                            PASSENGER_PUSH_ROUTE,
                            null,
                            null
                    )
            );
        }

        scheduleRideCleanup(rideRef, passengerId);
    }

    // Reemplaza el `.update({'status': 'tripCompleted', ...})` que antes
    // hacía driver_app directo sobre Realtime Database: se mueve acá para
    // verificar que quien finaliza es el conductor realmente asignado, y
    // para poder reutilizar la misma limpieza programada del nodo que usa
    // cancelRide (corre en el backend, así que no depende de que la app
    // siga abierta el tiempo suficiente).
    public void completeTrip(String passengerId, String driverUid) {
        DatabaseReference rideRef = firebaseDatabase.getReference(TAXI_REQUESTS_PATH).child(passengerId);
        CompletableFuture<RideTransactionOutcome> future = new CompletableFuture<>();
        AtomicReference<OperationAbortReason> abortReason = new AtomicReference<>();

        rideRef.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                if (currentData.getValue() == null) {
                    return Transaction.success(currentData);
                }

                String status = (String) currentData.child("status").getValue();
                // Solo se puede finalizar un viaje que el pasajero ya
                // confirmó que arrancó (botón "He llegado" -> pasajero
                // confirma "en camino" -> tripStarted). TripScreen solo
                // muestra "Finalizar viaje" en ese status, pero igual se
                // valida acá porque el cliente no es fuente de verdad.
                if (!"tripStarted".equals(status)) {
                    abortReason.set(OperationAbortReason.NOT_ALLOWED);
                    return Transaction.abort();
                }

                String assignedDriverId = (String) currentData.child("driver").child("data").child("id").getValue();
                if (!driverUid.equals(assignedDriverId)) {
                    abortReason.set(OperationAbortReason.FORBIDDEN);
                    return Transaction.abort();
                }

                currentData.child("status").setValue("tripCompleted");
                currentData.child("updatedAt").setValue(ServerValue.TIMESTAMP);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                future.complete(new RideTransactionOutcome(error, committed, currentData));
            }
        });

        RideTransactionOutcome outcome;
        try {
            outcome = future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido al finalizar el viaje de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideCompleteException("No se pudo finalizar el viaje", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("RideDebug | Error al finalizar el viaje de passengerId={}: {}", passengerId, e.getMessage(), e);
            throw new RideCompleteException("No se pudo finalizar el viaje", e);
        }

        if (outcome.error() != null) {
            log.error("RideDebug | Firebase rechazó la transacción de finalización de passengerId={}: {}", passengerId, outcome.error().getMessage());
            throw new RideCompleteException("No se pudo finalizar el viaje", outcome.error().toException());
        }

        DataSnapshot snapshot = outcome.currentData();
        if (snapshot == null || !snapshot.exists()) {
            throw new RideNotFoundException(passengerId);
        }

        if (abortReason.get() == OperationAbortReason.FORBIDDEN) {
            throw new RideForbiddenException(passengerId);
        }

        String finalStatus = (String) snapshot.child("status").getValue();
        if (!outcome.committed() || !"tripCompleted".equals(finalStatus)) {
            throw new RideAlreadyFinishedException(passengerId);
        }

        log.info("RideDebug | Viaje de passengerId={} finalizado por driverUid={}", passengerId, driverUid);
        scheduleRideCleanup(rideRef, passengerId);
    }

    // Borra el nodo taxi_requests/{passengerId} unos segundos después de
    // llegar a un status terminal (cancelado o finalizado), una vez que
    // ambos clientes ya tuvieron tiempo de recibir ese status por su
    // listener de Firebase. Corre en una transacción propia (no un
    // removeValue directo) para no pisar una solicitud nueva que el
    // pasajero pudiera haber creado en esa misma key mientras tanto: solo
    // borra si el status sigue siendo terminal.
    private void scheduleRideCleanup(DatabaseReference rideRef, String passengerId) {
        CompletableFuture.runAsync(() -> rideRef.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                if (currentData.getValue() == null) {
                    return Transaction.success(currentData);
                }
                if (TERMINAL_STATUSES.contains(currentData.child("status").getValue())) {
                    currentData.setValue(null);
                }
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot currentData) {
                if (error != null) {
                    log.error("RideDebug | No se pudo limpiar el nodo de passengerId={} tras finalizar/cancelar: {}", passengerId, error.getMessage());
                }
            }
        }), CompletableFuture.delayedExecutor(RIDE_CLEANUP_DELAY_SECONDS, TimeUnit.SECONDS));
    }

    // Escribe un mensaje de chat y avisa por push al otro participante.
    // Deriva el rol (driver/passenger) del propio token verificado, igual
    // que cancelRide -- el cliente nunca dice quién es, solo manda el texto.
    // El rideId lo resuelve el backend desde el nodo de Realtime Database
    // (fuente de verdad), nunca confía en un rideId que mande el cliente.
    public void sendChatMessage(String passengerId, String senderUid, String text) {
        DatabaseReference rideRef = firebaseDatabase.getReference(TAXI_REQUESTS_PATH).child(passengerId);
        DataSnapshot snapshot = readSnapshot(rideRef);
        if (snapshot == null || !snapshot.exists()) {
            throw new RideNotFoundException(passengerId);
        }

        String status = (String) snapshot.child("status").getValue();
        if (!ACTIVE_RIDE_STATUSES.contains(status)) {
            // 'pending' (sin conductor asignado todavía) también cae acá:
            // el chat solo tiene sentido una vez que hay alguien del otro
            // lado para leerlo.
            throw new RideAlreadyFinishedException(passengerId);
        }

        String assignedDriverId = (String) snapshot.child("driver").child("data").child("id").getValue();
        String senderRole;
        String recipientCollection;
        String recipientUid;
        if (senderUid.equals(passengerId)) {
            senderRole = "passenger";
            recipientCollection = DRIVERS_COLLECTION;
            recipientUid = assignedDriverId;
        } else if (senderUid.equals(assignedDriverId)) {
            senderRole = "driver";
            recipientCollection = PASSENGERS_COLLECTION;
            recipientUid = passengerId;
        } else {
            throw new RideForbiddenException(passengerId);
        }

        // El controller ya rechazó con 400 un texto null/blank antes de
        // llamar acá -- lo único que queda por sanear es el largo máximo.
        String trimmedText = text.trim();
        if (trimmedText.length() > CHAT_MESSAGE_MAX_LENGTH) {
            trimmedText = trimmedText.substring(0, CHAT_MESSAGE_MAX_LENGTH);
        }

        String rideId = (String) snapshot.child("rideId").getValue();
        Timestamp now = Timestamp.now();
        Timestamp expireAt = Timestamp.ofTimeSecondsAndNanos(
                now.getSeconds() + CHAT_RETENTION_DAYS * 24 * 60 * 60,
                now.getNanos()
        );

        Map<String, Object> messageData = new HashMap<>();
        messageData.put("senderId", senderUid);
        messageData.put("senderRole", senderRole);
        messageData.put("text", trimmedText);
        messageData.put("createdAt", now);
        messageData.put("expireAt", expireAt);

        try {
            firestore.collection(CHATS_COLLECTION)
                    .document(rideId)
                    .collection(MESSAGES_SUBCOLLECTION)
                    .add(messageData)
                    .get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("ChatDebug | Interrumpido al enviar mensaje en rideId={}: {}", rideId, e.getMessage(), e);
            throw new ChatMessageException("No se pudo enviar el mensaje", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("ChatDebug | Error al enviar mensaje en rideId={}: {}", rideId, e.getMessage(), e);
            throw new ChatMessageException("No se pudo enviar el mensaje", e);
        }

        log.info("ChatDebug | Mensaje enviado en rideId={} por senderRole={}", rideId, senderRole);

        String preview = trimmedText.length() > 80 ? trimmedText.substring(0, 80) + "…" : trimmedText;
        notifyByFcm(
                recipientCollection,
                recipientUid,
                new PushNotificationDTO(
                        "driver".equals(senderRole) ? "Tu conductor te escribió" : "Tu pasajero te escribió",
                        preview,
                        "driver".equals(senderRole) ? PASSENGER_PUSH_ROUTE : DRIVER_PUSH_ROUTE,
                        "chat_message",
                        rideId
                )
        );
    }

    // Reemplaza la lectura directa que hacía passenger_app sobre su propio
    // nodo taxi_requests/{passengerId}: mismo resultado, pero ahora la
    // identidad de "de quién es este viaje" sale del token verificado (el
    // caller solo puede consultar su propio uid) en vez de que el cliente
    // pase el id que quiera.
    public Map<String, Object> findActiveRideForPassenger(String passengerId) {
        DatabaseReference rideRef = firebaseDatabase.getReference(TAXI_REQUESTS_PATH).child(passengerId);
        DataSnapshot snapshot = readSnapshot(rideRef);
        if (snapshot == null || !snapshot.exists()) {
            return null;
        }

        String status = (String) snapshot.child("status").getValue();
        if (!PASSENGER_ACTIVE_STATUSES.contains(status)) {
            return null;
        }

        return asMap(snapshot);
    }

    // Reemplaza el escaneo completo de /taxi_requests que hacía driver_app
    // client-side (TripRepositoryImpl.findActiveTripForDriver): ese approach
    // descargaba al dispositivo del conductor la data de TODAS las
    // solicitudes activas (incluyendo pickup/nombre de pasajeros ajenos a
    // él) solo para filtrar localmente la suya. Acá el filtrado ocurre
    // server-side con el Admin SDK y solo se devuelve el viaje que
    // realmente le pertenece al conductor autenticado.
    public Map<String, Object> findActiveRideForDriver(String driverUid) {
        DatabaseReference requestsRef = firebaseDatabase.getReference(TAXI_REQUESTS_PATH);
        DataSnapshot snapshot = readSnapshot(requestsRef);
        if (snapshot == null || !snapshot.exists()) {
            return null;
        }

        for (DataSnapshot child : snapshot.getChildren()) {
            String status = (String) child.child("status").getValue();
            if (!ACTIVE_RIDE_STATUSES.contains(status)) {
                continue;
            }

            String assignedDriverId = (String) child.child("driver").child("data").child("id").getValue();
            if (driverUid.equals(assignedDriverId)) {
                return asMap(child);
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(DataSnapshot snapshot) {
        Object value = snapshot.getValue();
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value) : new LinkedHashMap<>();
    }

    // Lectura puntual (no transacción, no listener persistente) de un nodo de
    // Realtime Database. El Admin SDK Java no tiene un `.get()` bloqueante
    // como el SDK cliente -- se envuelve el listener de una sola vez en un
    // CompletableFuture con el mismo timeout usado en las transacciones de
    // este service.
    private DataSnapshot readSnapshot(DatabaseReference ref) {
        CompletableFuture<DataSnapshot> future = new CompletableFuture<>();

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                future.complete(snapshot);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                future.completeExceptionally(error.toException());
            }
        });

        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RideQueryException("No se pudo consultar el viaje activo", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RideQueryException("No se pudo consultar el viaje activo", e);
        }
    }

    private void notifyByFcm(String collection, String documentId, PushNotificationDTO payload) {
        try {
            DocumentSnapshot snapshot = firestore.collection(collection).document(documentId).get().get();
            if (!snapshot.exists()) {
                log.warn("RideDebug | No se encontró el documento {}/{} para notificarle", collection, documentId);
                return;
            }

            notificationService.sendPush(snapshot.getString("fcmToken"), payload);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RideDebug | Interrumpido al notificar a {}/{}: {}", collection, documentId, e.getMessage(), e);
        } catch (ExecutionException e) {
            log.error("RideDebug | Error al notificar a {}/{}: {}", collection, documentId, e.getMessage(), e);
        }
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static String sanitizeAddress(String rawAddress) {
        if (rawAddress == null) {
            return "";
        }
        return PLUS_CODE_PREFIX.matcher(rawAddress.trim()).replaceFirst("").trim();
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    private record RideTransactionOutcome(DatabaseError error, boolean committed, DataSnapshot currentData) {
    }
}
