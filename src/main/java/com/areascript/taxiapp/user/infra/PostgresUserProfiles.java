package com.areascript.taxiapp.user.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.user.domain.AppProfile;
import com.areascript.taxiapp.user.domain.DriverProfile;
import com.areascript.taxiapp.user.domain.PassengerProfile;
import com.areascript.taxiapp.user.domain.UserProfiles;
import com.areascript.taxiapp.user.domain.VehicleSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// Los perfiles de conductor y pasajero en Postgres (tablas drivers, passengers
// y vehicles). Ver PostgresUserAccounts sobre la condición.
@Component
@ConditionalOnProperty(name = "taxiapp.persistence.postgres", havingValue = "true", matchIfMissing = true)
public class PostgresUserProfiles implements UserProfiles {

    private static final Logger log = LoggerFactory.getLogger(PostgresUserProfiles.class);

    private final DriverRowRepository drivers;
    private final VehicleRowRepository vehicles;
    private final PassengerRowRepository passengers;

    public PostgresUserProfiles(
            DriverRowRepository drivers,
            VehicleRowRepository vehicles,
            PassengerRowRepository passengers) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.passengers = passengers;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DriverProfile> findDriver(UUID userId) {
        try {
            return drivers.findById(userId).map(this::toDriverProfile);
        } catch (DataAccessException e) {
            log.error("UserDebug | No se pudo leer el perfil de conductor {}: {}", userId, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo leer el perfil de conductor", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PassengerProfile> findPassenger(UUID userId) {
        try {
            return passengers.findById(userId).map(row ->
                    new PassengerProfile(row.getPhotoUrl(), row.getStatus(), row.getLanguage()));
        } catch (DataAccessException e) {
            log.error("UserDebug | No se pudo leer el perfil de pasajero {}: {}", userId, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo leer el perfil de pasajero", e);
        }
    }

    @Override
    @Transactional
    public boolean saveDevice(UUID userId, AppProfile app, String fcmToken, String language) {
        try {
            Instant now = Instant.now();
            if (app == AppProfile.DRIVER) {
                DriverRow row = drivers.findById(userId).orElse(null);
                if (row == null) {
                    return false;
                }
                if (fcmToken != null) {
                    row.setFcmToken(fcmToken);
                }
                if (language != null) {
                    row.setLanguage(language);
                }
                row.setUpdatedAt(now);
                return true;
            }
            PassengerRow row = passengers.findById(userId).orElse(null);
            if (row == null) {
                return false;
            }
            if (fcmToken != null) {
                row.setFcmToken(fcmToken);
            }
            if (language != null) {
                row.setLanguage(language);
            }
            row.setUpdatedAt(now);
            return true;
        } catch (DataAccessException e) {
            log.error("UserDebug | No se pudo guardar el dispositivo de {}: {}", userId, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo guardar el dispositivo", e);
        }
    }

    private DriverProfile toDriverProfile(DriverRow row) {
        VehicleSummary vehicle = row.getVehicleId() == null
                ? null
                : vehicles.findById(row.getVehicleId()).map(PostgresUserProfiles::toVehicle).orElse(null);
        return new DriverProfile(
                row.getPhoneNumber(),
                row.getPhotoUrl(),
                row.getRating(),
                row.getApprovalStatus(),
                row.getLanguage(),
                vehicle);
    }

    private static VehicleSummary toVehicle(VehicleRow row) {
        return new VehicleSummary(
                row.getVehicleId(),
                row.getPlate(),
                row.getBrand(),
                row.getModel(),
                row.getYear(),
                row.getColor(),
                row.getRegistrationNumber(),
                row.getVerificationStatus());
    }
}
