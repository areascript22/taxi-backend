package com.areascript.taxiapp.characterization.support;

import com.areascript.taxiapp.dispatch.domain.DriverPresence;
import com.areascript.taxiapp.dispatch.domain.NearbyDriver;
import com.areascript.taxiapp.dispatch.domain.PresenceState;
import com.areascript.taxiapp.ride.domain.GeoDistance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// La presencia de los conductores en memoria, para los tests HTTP (que corren
// sin Postgres). Solo conoce a los conductores que el test dio de alta con
// register(): el resto "no tiene perfil", igual que en la base.
public class FakeDriverPresence implements DriverPresence {

    public record Location(double latitude, double longitude, Instant updatedAt) {
    }

    private final Set<String> registered = new HashSet<>();
    private final Set<String> online = new HashSet<>();
    private final Set<String> onRide = new HashSet<>();
    private final Map<String, Location> locations = new HashMap<>();
    private Clock clock = Clock.systemUTC();

    // ---- DriverPresence ----

    @Override
    public Optional<PresenceState> state(String driverUid) {
        if (!registered.contains(driverUid)) {
            return Optional.empty();
        }
        return Optional.of(new PresenceState(online.contains(driverUid), onRide.contains(driverUid)));
    }

    @Override
    public boolean setOnline(String driverUid, boolean value) {
        if (!registered.contains(driverUid)) {
            return false;
        }
        if (value) {
            online.add(driverUid);
        } else {
            online.remove(driverUid);
            locations.remove(driverUid);
        }
        return true;
    }

    @Override
    public void setOnRide(String driverUid, boolean value) {
        if (!registered.contains(driverUid)) {
            return;
        }
        if (value) {
            onRide.add(driverUid);
        } else {
            onRide.remove(driverUid);
        }
    }

    @Override
    public boolean recordLocationIfAvailable(String driverUid, double latitude, double longitude) {
        if (!registered.contains(driverUid) || !online.contains(driverUid) || onRide.contains(driverUid)) {
            return false;
        }
        locations.put(driverUid, new Location(latitude, longitude, clock.instant()));
        return true;
    }

    @Override
    public List<NearbyDriver> findAvailableNear(
            double latitude, double longitude, int radiusMeters, Duration maxAge, int limit,
            Collection<String> excludeUids) {
        Instant oldest = clock.instant().minus(maxAge);
        List<NearbyDriver> found = new ArrayList<>();
        locations.forEach((uid, location) -> {
            if (!online.contains(uid) || onRide.contains(uid) || excludeUids.contains(uid)
                    || location.updatedAt().isBefore(oldest)) {
                return;
            }
            double meters = GeoDistance.metersBetween(latitude, longitude, location.latitude(), location.longitude());
            if (meters <= radiusMeters) {
                found.add(new NearbyDriver(uid, meters));
            }
        });
        found.sort(Comparator.comparingDouble(NearbyDriver::distanceMeters));
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    // ---- Para el arnés y las aserciones ----

    public void register(String driverUid) {
        registered.add(driverUid);
    }

    public boolean isOnline(String driverUid) {
        return online.contains(driverUid);
    }

    public boolean isOnRide(String driverUid) {
        return onRide.contains(driverUid);
    }

    public Location locationOf(String driverUid) {
        return locations.get(driverUid);
    }

    public void clear() {
        registered.clear();
        online.clear();
        onRide.clear();
        locations.clear();
    }
}
