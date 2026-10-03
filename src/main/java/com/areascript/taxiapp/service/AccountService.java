package com.areascript.taxiapp.service;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final RideService rideService;
    private final AccountCleanupService accountCleanupService;
    private final FirebaseAuth firebaseAuth;

    public AccountService(
            RideService rideService,
            AccountCleanupService accountCleanupService,
            FirebaseAuth firebaseAuth
    ) {
        this.rideService = rideService;
        this.accountCleanupService = accountCleanupService;
        this.firebaseAuth = firebaseAuth;
    }

    // Reusa los mismos chequeos de viaje activo que ya existen para resumir
    // sesión (ver RideController /passenger/active y /driver/active): si hay
    // un viaje en curso, se rechaza el borrado en vez de cancelarlo a la
    // fuerza -- la otra parte (pasajero/conductor) puede estar en medio del
    // viaje con esta persona.
    //
    // A diferencia de DriverAdminService.deleteDriver (que borra Auth primero,
    // porque ahí el admin puede reintentar sin depender de un token de un
    // tercero), acá se borra Auth AL FINAL: si wipeUserData falla a mitad de
    // camino, el propio usuario todavía tiene un token válido y puede volver
    // a llamar a este mismo endpoint para reintentar el borrado completo.
    public void deleteOwnAccount(String uid) {
        if (rideService.findActiveRideForDriver(uid) != null
                || rideService.findActiveRideForPassenger(uid) != null) {
            throw new AccountHasActiveRideException(uid);
        }

        accountCleanupService.wipeUserData(uid);

        try {
            firebaseAuth.deleteUser(uid);
        } catch (FirebaseAuthException e) {
            // USER_NOT_FOUND no es un error acá: en un DELETE, "el usuario ya
            // no existe" ES el estado deseado, así que el endpoint responde
            // igual 204 (mismo criterio idempotente que cancelRide). Esto
            // pasa cuando la misma llamada entra dos veces -- por ejemplo si
            // el RetryInterceptor del cliente reintenta tras un timeout: el
            // primer intento completó el borrado aunque el cliente dejó de
            // escuchar la respuesta, y el reintento ya no encuentra al
            // usuario. Antes esto devolvía 500 y la app mostraba un error
            // sobre una cuenta que sí había quedado borrada.
            //
            // Ojo: a diferencia de DriverAdminService.deleteDriver, acá NO se
            // traduce a 404 -- ahí el admin necesita saber que el conductor
            // que quiso borrar no existía; acá el dueño de la cuenta solo
            // necesita saber que su cuenta ya no está.
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                log.info("AccountDebug | uid={} ya no existía en Firebase Authentication (borrado previo); se trata como éxito", uid);
                return;
            }
            log.error("AccountDebug | Error al eliminar uid={} de Firebase Authentication: {}", uid, e.getMessage(), e);
            throw new AccountDeletionException("No se pudo eliminar el usuario de Firebase Authentication", e);
        }

        log.info("AccountDebug | Cuenta uid={} eliminada completamente", uid);
    }
}
