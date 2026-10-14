package com.areascript.taxiapp.user.application;

import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.common.error.NotFoundException;
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
// pasajero tiene que ir al segundo. Por eso se guardan en el perfil de la app
// indicada, no en la persona.
@Service
public class DeviceRegistrationService {

    // Los idiomas que sabe armar el catálogo de mensajes del servidor.
    static final Set<String> LANGUAGES = Set.of("es", "en");
    // Coincide con el CHECK de la migración V3.
    static final int MAX_TOKEN_LENGTH = 1024;

    private final UserAccounts accounts;
    private final UserProfiles profiles;

    public DeviceRegistrationService(UserAccounts accounts, UserProfiles profiles) {
        this.accounts = accounts;
        this.profiles = profiles;
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

        // No se crea un perfil a medias: el perfil nace con el registro
        // (PUT /api/me/driver-profile o /passenger-profile).
        if (!profiles.saveDevice(account.id(), appProfile, fcmToken, language)) {
            throw new NotFoundException(appProfile == AppProfile.DRIVER
                    ? "Todavía no tienes perfil de conductor"
                    : "Todavía no tienes perfil de pasajero");
        }
    }
}
