package com.areascript.taxiapp.characterization.support;

import com.areascript.taxiapp.user.domain.AppProfile;
import com.areascript.taxiapp.user.domain.DriverProfile;
import com.areascript.taxiapp.user.domain.PassengerProfile;
import com.areascript.taxiapp.user.domain.UserProfiles;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Los perfiles de conductor y pasajero en memoria para los tests HTTP.
public class FakeUserProfiles implements UserProfiles {

    public record SavedDevice(UUID userId, AppProfile app, String fcmToken, String language) {
    }

    private final Map<UUID, DriverProfile> drivers = new HashMap<>();
    private final Map<UUID, PassengerProfile> passengers = new HashMap<>();
    private final java.util.List<SavedDevice> savedDevices = new java.util.ArrayList<>();

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

    // ---- Para el arnés y las aserciones ----

    public void putDriver(UUID userId, DriverProfile profile) {
        drivers.put(userId, profile);
    }

    public void putPassenger(UUID userId, PassengerProfile profile) {
        passengers.put(userId, profile);
    }

    public java.util.List<SavedDevice> savedDevices() {
        return savedDevices;
    }

    public void clear() {
        drivers.clear();
        passengers.clear();
        savedDevices.clear();
    }
}
