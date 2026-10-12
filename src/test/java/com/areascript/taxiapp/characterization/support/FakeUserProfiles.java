package com.areascript.taxiapp.characterization.support;

import com.areascript.taxiapp.user.domain.AppProfile;
import com.areascript.taxiapp.user.domain.DriverProfile;
import com.areascript.taxiapp.user.domain.DriverProfileData;
import com.areascript.taxiapp.user.domain.PassengerProfile;
import com.areascript.taxiapp.user.domain.PassengerProfileData;
import com.areascript.taxiapp.user.domain.UserProfiles;
import com.areascript.taxiapp.user.domain.VehicleData;
import com.areascript.taxiapp.user.domain.VehicleSummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Los perfiles de conductor y pasajero en memoria para los tests HTTP.
public class FakeUserProfiles implements UserProfiles {

    public record SavedDevice(UUID userId, AppProfile app, String fcmToken, String language) {
    }

    public record SavedDriverProfile(UUID userId, String email, DriverProfileData data) {
    }

    public record SavedPassengerProfile(UUID userId, String email, PassengerProfileData data) {
    }

    private final Map<UUID, DriverProfile> drivers = new HashMap<>();
    private final Map<UUID, PassengerProfile> passengers = new HashMap<>();
    private final List<SavedDevice> savedDevices = new ArrayList<>();
    private final List<SavedDriverProfile> savedDriverProfiles = new ArrayList<>();
    private final List<SavedPassengerProfile> savedPassengerProfiles = new ArrayList<>();

    @Override
    public Optional<DriverProfile> findDriver(UUID userId) {
        return Optional.ofNullable(drivers.get(userId));
    }

    @Override
    public Optional<PassengerProfile> findPassenger(UUID userId) {
        return Optional.ofNullable(passengers.get(userId));
    }

    @Override
    public boolean saveDevice(UUID userId, AppProfile app, String fcmToken, String language) {
        boolean hasProfile = app == AppProfile.DRIVER ? drivers.containsKey(userId) : passengers.containsKey(userId);
        if (hasProfile) {
            savedDevices.add(new SavedDevice(userId, app, fcmToken, language));
        }
        return hasProfile;
    }

    // Igual que la base: al CREAR el perfil nace "pending"; al editar se
    // conserva la aprobación que ya tenía.
    @Override
    public void saveDriverProfile(UUID userId, String email, DriverProfileData data) {
        savedDriverProfiles.add(new SavedDriverProfile(userId, email, data));
        DriverProfile existing = drivers.get(userId);
        VehicleData v = data.vehicle();
        VehicleSummary vehicle = new VehicleSummary(
                existing != null && existing.vehicle() != null ? existing.vehicle().vehicleId() : "v-" + userId,
                v.plate(), v.brand(), v.model(), v.year(), v.color(), v.registrationNumber(),
                "pending");
        drivers.put(userId, new DriverProfile(
                data.phoneNumber(),
                data.photoUrl(),
                existing == null ? 5.0 : existing.rating(),
                existing == null ? "pending" : existing.approvalStatus(),
                existing == null ? null : existing.language(),
                vehicle,
                existing != null && existing.online(),
                existing != null && existing.onRide()));
    }

    @Override
    public void savePassengerProfile(UUID userId, String email, PassengerProfileData data) {
        savedPassengerProfiles.add(new SavedPassengerProfile(userId, email, data));
        PassengerProfile existing = passengers.get(userId);
        passengers.put(userId, new PassengerProfile(
                data.photoUrl(),
                existing == null ? "active" : existing.status(),
                existing == null ? null : existing.language()));
    }

    // ---- Para el arnés y las aserciones ----

    public void putDriver(UUID userId, DriverProfile profile) {
        drivers.put(userId, profile);
    }

    public void putPassenger(UUID userId, PassengerProfile profile) {
        passengers.put(userId, profile);
    }

    public List<SavedDevice> savedDevices() {
        return savedDevices;
    }

    public List<SavedDriverProfile> savedDriverProfiles() {
        return savedDriverProfiles;
    }

    public List<SavedPassengerProfile> savedPassengerProfiles() {
        return savedPassengerProfiles;
    }

    public void clear() {
        drivers.clear();
        passengers.clear();
        savedDevices.clear();
        savedDriverProfiles.clear();
        savedPassengerProfiles.clear();
    }
}
