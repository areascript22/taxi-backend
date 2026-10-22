package com.areascript.taxiapp.user.application;

import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.common.firebase.FirebaseProperties;
import com.areascript.taxiapp.user.domain.DriverProfileData;
import com.areascript.taxiapp.user.domain.MyProfile;
import com.areascript.taxiapp.user.domain.PassengerProfileData;
import com.areascript.taxiapp.user.domain.ProfilePhotoUrl;
import com.areascript.taxiapp.user.domain.UserAccount;
import com.areascript.taxiapp.user.domain.UserAccounts;
import com.areascript.taxiapp.user.domain.UserProfiles;
import com.areascript.taxiapp.user.domain.VehicleData;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Year;
import java.util.regex.Pattern;

// Alta y edición del perfil de conductor y de pasajero.
//
// Es el ÚNICO camino para escribir un perfil: las apps ya no tocan Firestore.
// Acá se validan los datos (con los mismos límites que las restricciones de la
// base, para responder 400 con un mensaje útil y no un 500 por un CHECK) y la
// foto, que el cliente sube a Storage por su cuenta y de la que solo manda la
// URL.
//
// Qué NO puede decidir el cliente: la aprobación (nace "pending"), el rol, el
// bloqueo y la calificación. Por eso el request no los tiene.
@Service
public class ProfileRegistrationService {

    static final String DRIVER_PHOTOS_FOLDER = "driver_profile_photos";
    static final String PASSENGER_PHOTOS_FOLDER = "passenger_profile_photos";

    // Los límites coinciden con los CHECK de la migración V3.
    static final int MAX_NAME = 100;
    static final int MAX_PHONE = 20;
    static final int MAX_PLATE = 15;
    static final int MAX_BRAND = 50;
    static final int MAX_MODEL = 50;
    static final int MAX_COLOR = 30;
    static final int MAX_REGISTRATION = 30;
    static final int MIN_YEAR = 1950;

    // Dígitos, "+", espacios, guiones y paréntesis: lo que se escribe en un
    // teléfono. No se exige un formato por país.
    private static final Pattern PHONE = Pattern.compile("[0-9+() \\-]{7,20}");

    private final UserAccounts accounts;
    private final UserProfiles profiles;
    private final FirebaseProperties firebase;
    private final TaxiappProperties.Admin adminSettings;
    private final Clock clock;

    public ProfileRegistrationService(
            UserAccounts accounts,
            UserProfiles profiles,
            FirebaseProperties firebase,
            TaxiappProperties taxiapp,
            Clock clock) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.firebase = firebase;
        this.adminSettings = taxiapp.admin();
        this.clock = clock;
    }

    public MyProfile saveDriverProfile(
            String firebaseUid,
            String email,
            String displayName,
            DriverProfileData data) {
        DriverProfileData clean = validate(firebaseUid, data);

        UserAccount account = accounts.findOrProvision(firebaseUid, email, displayName);
        // El fundador llena sus datos y su vehículo como cualquier conductor; lo
        // único que se salta es la espera de aprobación (nadie puede aprobarlo).
        // Lo decide la configuración del servidor con el uid del token verificado,
        // nunca algo que mande el cliente.
        profiles.saveDriverProfile(account.id(), email, clean, adminSettings.isFounder(firebaseUid));

        return load(account);
    }

    public MyProfile savePassengerProfile(
            String firebaseUid,
            String email,
            String displayName,
            PassengerProfileData data) {
        PassengerProfileData clean = validate(firebaseUid, data);

        UserAccount account = accounts.findOrProvision(firebaseUid, email, displayName);
        profiles.savePassengerProfile(account.id(), email, clean);

        return load(account);
    }

    private MyProfile load(UserAccount account) {
        // Se vuelve a leer la persona: nombre y apellido acaban de cambiar.
        UserAccount fresh = accounts.findOrProvision(account.firebaseUid(), account.email(), account.displayName());
        return new MyProfile(
                fresh,
                profiles.findDriver(fresh.id()).orElse(null),
                profiles.findPassenger(fresh.id()).orElse(null));
    }

    // ---- Validación ----

    private DriverProfileData validate(String firebaseUid, DriverProfileData data) {
        if (data == null) {
            throw new InvalidInputException("Faltan los datos del conductor");
        }
        String phone = required(data.phoneNumber(), "El teléfono", MAX_PHONE);
        if (!PHONE.matcher(phone).matches()) {
            throw new InvalidInputException("El teléfono no es válido");
        }

        if (data.vehicle() == null) {
            throw new InvalidInputException("Faltan los datos del vehículo");
        }
        VehicleData vehicle = validate(data.vehicle());

        return new DriverProfileData(
                required(data.firstName(), "El nombre", MAX_NAME),
                required(data.lastName(), "El apellido", MAX_NAME),
                phone,
                photo(data.photoUrl(), DRIVER_PHOTOS_FOLDER, firebaseUid),
                vehicle);
    }

    private PassengerProfileData validate(String firebaseUid, PassengerProfileData data) {
        if (data == null) {
            throw new InvalidInputException("Faltan los datos del pasajero");
        }
        return new PassengerProfileData(
                required(data.firstName(), "El nombre", MAX_NAME),
                required(data.lastName(), "El apellido", MAX_NAME),
                photo(data.photoUrl(), PASSENGER_PHOTOS_FOLDER, firebaseUid));
    }

    private VehicleData validate(VehicleData vehicle) {
        int maxYear = Year.now(clock).getValue() + 1;
        if (vehicle.year() < MIN_YEAR || vehicle.year() > maxYear) {
            throw new InvalidInputException(
                    "El año del vehículo debe estar entre " + MIN_YEAR + " y " + maxYear);
        }
        return new VehicleData(
                required(vehicle.plate(), "La placa", MAX_PLATE),
                required(vehicle.brand(), "La marca", MAX_BRAND),
                required(vehicle.model(), "El modelo", MAX_MODEL),
                vehicle.year(),
                required(vehicle.color(), "El color", MAX_COLOR),
                optional(vehicle.registrationNumber(), "La matrícula", MAX_REGISTRATION));
    }

    private static String required(String value, String label, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidInputException(label + " es obligatorio");
        }
        if (trimmed.length() > max) {
            throw new InvalidInputException(label + " no puede tener más de " + max + " caracteres");
        }
        return trimmed;
    }

    private static String optional(String value, String label, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > max) {
            throw new InvalidInputException(label + " no puede tener más de " + max + " caracteres");
        }
        return trimmed;
    }

    // Sin foto es válido. Con foto, tiene que ser la de ESTA persona (ver
    // ProfilePhotoUrl para el porqué).
    private String photo(String url, String folder, String firebaseUid) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String bucket = firebase.storage().isConfigured() ? firebase.storage().bucketName() : null;
        if (!ProfilePhotoUrl.belongsTo(url, bucket, folder, firebaseUid)) {
            throw new InvalidInputException("La foto de perfil no es válida");
        }
        return url.trim();
    }
}
