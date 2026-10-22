package com.areascript.taxiapp.user.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.driveradmin.domain.ApprovalStatus;
import com.areascript.taxiapp.driveradmin.domain.DriverApprovalRules;
import com.areascript.taxiapp.user.domain.AppProfile;
import com.areascript.taxiapp.user.domain.DriverProfile;
import com.areascript.taxiapp.user.domain.DriverProfileData;
import com.areascript.taxiapp.user.domain.PassengerProfile;
import com.areascript.taxiapp.user.domain.PassengerProfileData;
import com.areascript.taxiapp.user.domain.UserProfiles;
import com.areascript.taxiapp.user.domain.VehicleData;
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

    private final UserRowRepository users;
    private final DriverRowRepository drivers;
    private final VehicleRowRepository vehicles;
    private final PassengerRowRepository passengers;

    public PostgresUserProfiles(
            UserRowRepository users,
            DriverRowRepository drivers,
            VehicleRowRepository vehicles,
            PassengerRowRepository passengers) {
        this.users = users;
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

    @Override
    @Transactional
    public void saveDriverProfile(UUID userId, String email, DriverProfileData data, boolean autoApprove) {
        try {
            Instant now = Instant.now();
            applyIdentity(userId, email, data.firstName(), data.lastName(), now);

            DriverRow driver = drivers.findById(userId).orElse(null);
            // null = alta. La regla decide el estado; este método solo la aplica.
            ApprovalStatus current = driver == null ? null : ApprovalStatus.fromStored(driver.getApprovalStatus());
            ApprovalStatus next = DriverApprovalRules.afterProfileSave(current, autoApprove);
            if (driver == null) {
                // Alta: sin bloqueo ni calificación propia. La aprobación la fija el
                // servidor, no el cliente.
                driver = new DriverRow(userId);
                driver.setCreatedAt(now);
            }
            if (next != current) {
                driver.setApprovalStatus(next.wireValue());
                if (next == ApprovalStatus.APPROVED) {
                    // Solo llega aquí el fundador (autoApprove): se aprueba a sí
                    // mismo por configuración, y no por la API de moderación. Un
                    // rechazo anterior deja de aplicar.
                    driver.setApprovedAt(now);
                    driver.setApprovedBy(userId);
                    driver.setRejectionReason(null);
                }
            }
            driver.setPhoneNumber(data.phoneNumber());
            driver.setPhotoUrl(data.photoUrl());
            driver.setUpdatedAt(now);

            VehicleRow vehicle = driver.getVehicleId() == null
                    ? null
                    : vehicles.findById(driver.getVehicleId()).orElse(null);
            if (vehicle == null) {
                vehicle = new VehicleRow(UUID.randomUUID().toString(), userId);
                vehicle.setCreatedAt(now);
                vehicle.setVerificationStatus("pending");
            } else if (changesIdentity(vehicle, data.vehicle())) {
                // Otra placa u otra matrícula es otro auto (u otro documento):
                // hay que volver a verificarlo.
                vehicle.setVerificationStatus("pending");
            }
            VehicleData declared = data.vehicle();
            vehicle.setPlate(declared.plate());
            vehicle.setBrand(declared.brand());
            vehicle.setModel(declared.model());
            vehicle.setYear(declared.year());
            vehicle.setColor(declared.color());
            vehicle.setRegistrationNumber(declared.registrationNumber());
            vehicle.setUpdatedAt(now);

            // El vehículo antes que el conductor que lo referencia (clave foránea).
            // Pero vehicles.driver_id referencia a drivers: el conductor tiene que
            // existir primero. Se guarda el conductor sin vehículo, luego el
            // vehículo, y por último se enlaza.
            String currentVehicleId = driver.getVehicleId();
            driver.setVehicleId(currentVehicleId);
            drivers.saveAndFlush(driver);
            vehicles.saveAndFlush(vehicle);
            if (!vehicle.getVehicleId().equals(currentVehicleId)) {
                driver.setVehicleId(vehicle.getVehicleId());
                drivers.saveAndFlush(driver);
            }
        } catch (DataAccessException e) {
            log.error("UserDebug | No se pudo guardar el perfil de conductor {}: {}", userId, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo guardar el perfil de conductor", e);
        }
    }

    @Override
    @Transactional
    public void savePassengerProfile(UUID userId, String email, PassengerProfileData data) {
        try {
            Instant now = Instant.now();
            applyIdentity(userId, email, data.firstName(), data.lastName(), now);

            PassengerRow passenger = passengers.findById(userId).orElse(null);
            if (passenger == null) {
                passenger = new PassengerRow(userId);
                passenger.setStatus("active");
                passenger.setCreatedAt(now);
            }
            passenger.setPhotoUrl(data.photoUrl());
            passenger.setUpdatedAt(now);
            passengers.saveAndFlush(passenger);
        } catch (DataAccessException e) {
            log.error("UserDebug | No se pudo guardar el perfil de pasajero {}: {}", userId, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo guardar el perfil de pasajero", e);
        }
    }

    // Nombre y apellido son de la PERSONA (los comparten sus dos perfiles). El
    // correo solo se completa si todavía no estaba: lo fija el token, no el
    // formulario.
    private void applyIdentity(UUID userId, String email, String firstName, String lastName, Instant now) {
        UserRow user = users.findById(userId).orElseThrow();
        user.setFirstName(firstName);
        user.setLastName(lastName);
        if ((user.getEmail() == null || user.getEmail().isBlank()) && email != null) {
            user.setEmail(email);
        }
        user.setUpdatedAt(now);
        users.saveAndFlush(user);
    }

    private static boolean changesIdentity(VehicleRow current, VehicleData declared) {
        return !current.getPlate().equals(declared.plate())
                || !current.getRegistrationNumber().equals(declared.registrationNumber());
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
                row.getRejectionReason(),
                row.getLanguage(),
                vehicle,
                row.isOnline(),
                row.isOnRide());
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
