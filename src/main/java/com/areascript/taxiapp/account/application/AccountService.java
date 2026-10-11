package com.areascript.taxiapp.account.application;

import com.areascript.taxiapp.account.domain.ActiveRideLookup;
import com.areascript.taxiapp.account.domain.UserDataCleaner;
import com.areascript.taxiapp.common.error.ConflictException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.identity.domain.IdentityProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final ActiveRideLookup activeRides;
    private final UserDataCleaner dataCleaner;
    private final IdentityProvider identityProvider;

    public AccountService(
            ActiveRideLookup activeRides,
            UserDataCleaner dataCleaner,
            IdentityProvider identityProvider
    ) {
        this.activeRides = activeRides;
        this.dataCleaner = dataCleaner;
        this.identityProvider = identityProvider;
    }

    // Si hay un viaje en curso se rechaza el borrado en vez de cancelarlo a la
    // fuerza -- la otra parte (pasajero/conductor) puede estar en medio del
    // viaje con esta persona.
    //
    // A diferencia de DriverAdminService.deleteDriver (que borra la cuenta
    // primero, porque ahí el admin puede reintentar sin depender de un token
    // de un tercero), acá se borra la cuenta AL FINAL: si el borrado de datos
    // falla a mitad de camino, el propio usuario todavía tiene un token válido
    // y puede volver a llamar a este mismo endpoint para reintentar el borrado
    // completo.
    public void deleteOwnAccount(String uid) {
        if (activeRides.hasActiveRide(uid)) {
            throw new ConflictException("No se puede eliminar la cuenta uid=" + uid + ": tiene un viaje activo");
        }

        dataCleaner.wipe(uid);

        try {
            identityProvider.delete(uid);
        } catch (NotFoundException e) {
            // "El usuario ya no existe" ES el estado deseado, así que el
            // endpoint responde igual 204 (mismo criterio idempotente que
            // cancelRide). Pasa cuando la misma llamada entra dos veces -- por
            // ejemplo si el RetryInterceptor del cliente reintenta tras un
            // timeout: el primer intento completó el borrado aunque el cliente
            // dejó de escuchar la respuesta, y el reintento ya no encuentra al
            // usuario.
            //
            // A diferencia de DriverAdminService.deleteDriver, acá NO se
            // traduce a 404: allí el admin necesita saber que el conductor
            // que quiso borrar no existía; acá el dueño de la cuenta solo
            // necesita saber que su cuenta ya no está.
            log.info("AccountDebug | uid={} ya no existía en Firebase Authentication (borrado previo); se trata como éxito", uid);
            return;
        }

        log.info("AccountDebug | Cuenta uid={} eliminada completamente", uid);
    }
}
