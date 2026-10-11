package com.areascript.taxiapp.user.application;

import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.notification.domain.Device;
import com.areascript.taxiapp.notification.domain.DeviceWriter;
import com.areascript.taxiapp.notification.domain.Recipient;
import com.areascript.taxiapp.user.domain.AppProfile;
import com.areascript.taxiapp.user.domain.UserAccount;
import com.areascript.taxiapp.user.domain.UserAccounts;
import com.areascript.taxiapp.user.domain.UserProfiles;
import org.springframework.stereotype.Service;

import java.util.Set;

// Registra el dispositivo (token de notificaciones e idioma) de la persona en la
// app desde la que llama.
//
// El token y el idioma son por INSTALACIÓN: la misma persona con la misma cuenta
// tiene uno en la app de conductor y otro en la de pasajero, y el push al
// pasajero tiene que ir al segundo.
//
// Transición: se guarda en Postgres Y en Firestore. Las notificaciones todavía
// leen de Firestore (DeviceRegistry/DeviceWriter) y las apps viejas siguen escribiendo ahí;
// cuando se suba la versión mínima y el registro lea de Postgres, desaparece la
// escritura en Firestore.
@Service
public class DeviceRegistrationService {

    // Los idiomas que sabe armar el catálogo de mensajes del servidor.
    static final Set<String> LANGUAGES = Set.of("es", "en");
    // Coincide con el CHECK de la migración V3.
    static final int MAX_TOKEN_LENGTH = 1024;

    private final UserAccounts accounts;
    private final UserProfiles profiles;
    private final DeviceWriter registry;

    public DeviceRegistrationService(UserAccounts accounts, UserProfiles profiles, DeviceWriter registry) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.registry = registry;
    }

    public void register(
            String firebaseUid,
            String email,
            String displayName,
            String app,
            String fcmToken,
            String language) {

        AppProfile appProfile = AppProfile.parse(app).orElseThrow(() -> new InvalidInputException(
                "App inválida: '" + app + "'. Valores permitidos: driver, passenger"));
        if (fcmToken == null && language == null) {
            throw new InvalidInputException("Se requiere fcmToken, language o ambos");
        }
        if (fcmToken != null && (fcmToken.isBlank() || fcmToken.length() > MAX_TOKEN_LENGTH)) {
            throw new InvalidInputException("fcmToken inválido");
        }
        if (language != null && !LANGUAGES.contains(language)) {
            throw new InvalidInputException("Idioma inválido: '" + language + "'. Valores permitidos: es, en");
        }

        UserAccount account = accounts.findOrProvision(firebaseUid, email, displayName);

        boolean inPostgres = profiles.saveDevice(account.id(), appProfile, fcmToken, language);
        Recipient recipient = appProfile == AppProfile.DRIVER
                ? Recipient.driver(firebaseUid)
                : Recipient.passenger(firebaseUid);
        boolean inFirestore = registry.save(recipient, new Device(fcmToken, language));

        if (!inPostgres && !inFirestore) {
            throw new NotFoundException(appProfile == AppProfile.DRIVER
                    ? "Todavía no tienes perfil de conductor"
                    : "Todavía no tienes perfil de pasajero");
        }
    }
}
