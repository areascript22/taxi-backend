package com.areascript.taxiapp.bootstrap;

import com.areascript.taxiapp.driveradmin.infra.DriverRow;
import com.areascript.taxiapp.driveradmin.infra.DriverRowRepository;
import com.areascript.taxiapp.driveradmin.infra.VehicleRow;
import com.areascript.taxiapp.driveradmin.infra.VehicleRowRepository;
import com.areascript.taxiapp.passenger.infra.PassengerRow;
import com.areascript.taxiapp.passenger.infra.PassengerRowRepository;
import com.areascript.taxiapp.user.infra.UserRow;
import com.areascript.taxiapp.user.infra.UserRowRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Copia los perfiles (ya leídos de Firestore como mapas) a Postgres.
//
// Es IDEMPOTENTE: se puede correr varias veces y cada fila se sobrescribe con
// lo que haya en Firestore en ese momento. Una persona se identifica por su uid
// de Firebase (users.firebase_uid), así que en la segunda corrida conserva su
// users.id. No borra nada: un conductor que ya no está en Firestore se queda en
// Postgres.
//
// Una misma persona puede estar en drivers Y en passengers (misma cuenta de
// Google): es UNA fila en users con dos perfiles. Si los nombres difieren,
// gana el del conductor.
//
// No abre su propia transacción: el que lo invoca decide el alcance (el
// importador de arranque lo envuelve todo en una, para que un fallo a medias no
// deje la copia a la mitad).
class ProfileImportService {

    private final UserRowRepository users;
    private final DriverRowRepository drivers;
    private final VehicleRowRepository vehicles;
    private final PassengerRowRepository passengers;

    ProfileImportService(
            UserRowRepository users,
            DriverRowRepository drivers,
            VehicleRowRepository vehicles,
            PassengerRowRepository passengers) {
        this.users = users;
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.passengers = passengers;
    }

    record Report(
            int users,
            int drivers,
            int vehicles,
            int vehiclesSkipped,
            int driversLinkedToVehicle,
            int passengers,
            List<String> warnings) {
    }

    // Los tres argumentos son "id del documento -> sus campos". Para conductores
    // y pasajeros el id del documento es el uid de Firebase.
    Report importProfiles(
            Map<String, Map<String, Object>> driverDocs,
            Map<String, Map<String, Object>> vehicleDocs,
            Map<String, Map<String, Object>> passengerDocs,
            Instant importedAt) {

        List<String> warnings = new ArrayList<>();

        // 1) users: una fila por persona. Las que ya existen en Postgres
        //    conservan su id; las nuevas reciben uno.
        Set<String> firebaseUids = new LinkedHashSet<>(driverDocs.keySet());
        firebaseUids.addAll(passengerDocs.keySet());

        Map<String, UserRow> byFirebaseUid = new HashMap<>();
        users.findAllByFirebaseUidIn(firebaseUids)
                .forEach(user -> byFirebaseUid.put(user.getFirebaseUid(), user));

        driverDocs.forEach((uid, data) -> {
            UserRow row = byFirebaseUid.computeIfAbsent(uid, key -> new UserRow(UUID.randomUUID(), key));
            FirestoreProfileMapper.fillUserFromDriver(row, data, importedAt);
        });
        passengerDocs.forEach((uid, data) -> {
            if (driverDocs.containsKey(uid)) {
                return; // los datos de identidad del conductor ganan
            }
            UserRow row = byFirebaseUid.computeIfAbsent(uid, key -> new UserRow(UUID.randomUUID(), key));
            FirestoreProfileMapper.fillUserFromPassenger(row, data, importedAt);
        });

        // blocked_by apunta a OTRA fila de users: se completa en una segunda
        // pasada, cuando todas ya existen.
        Map<String, UserRow> savedUsers = new HashMap<>();
        for (UserRow saved : users.saveAll(byFirebaseUid.values())) {
            savedUsers.put(saved.getFirebaseUid(), saved);
        }
        users.flush();

        driverDocs.forEach((uid, data) -> {
            String actorUid = FirestoreProfileMapper.nullableText(data, "blockedBy");
            savedUsers.get(uid).setBlockedBy(resolveActor(actorUid, savedUsers));
        });
        users.flush();

        // 2) drivers: cuelgan del users.id de su persona.
        List<DriverRow> driverRows = new ArrayList<>();
        driverDocs.forEach((uid, data) -> {
            DriverRow row = FirestoreProfileMapper.toDriver(savedUsers.get(uid).getId(), data, importedAt);
            row.setApprovedBy(resolveActor(FirestoreProfileMapper.nullableText(data, "approvedBy"), savedUsers));
            row.setRejectedBy(resolveActor(FirestoreProfileMapper.nullableText(data, "rejectedBy"), savedUsers));
            driverRows.add(row);
        });
        Map<UUID, DriverRow> savedDrivers = new HashMap<>();
        for (DriverRow saved : drivers.saveAll(driverRows)) {
            savedDrivers.put(saved.getUserId(), saved);
        }
        drivers.flush();

        // 3) vehículos. Uno sin dueño, o cuyo dueño no es un conductor, no se
        //    puede guardar: se salta y se avisa (no es un error de la importación).
        List<VehicleRow> vehicleRows = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<String, Map<String, Object>> entry : vehicleDocs.entrySet()) {
            String ownerUid = FirestoreProfileMapper.ownerOf(entry.getValue());
            if (ownerUid == null) {
                warnings.add("vehículo " + entry.getKey() + ": no tiene driverId, se omite");
                skipped++;
            } else if (!driverDocs.containsKey(ownerUid)) {
                warnings.add("vehículo " + entry.getKey() + ": su conductor '" + ownerUid
                        + "' no existe en drivers, se omite");
                skipped++;
            } else {
                UUID ownerId = savedUsers.get(ownerUid).getId();
                vehicleRows.add(FirestoreProfileMapper.toVehicle(entry.getKey(), ownerId, entry.getValue(), importedAt));
            }
        }
        List<VehicleRow> savedVehicles = vehicles.saveAll(vehicleRows);
        vehicles.flush();
        Map<String, VehicleRow> vehiclesById = new HashMap<>();
        savedVehicles.forEach(vehicle -> vehiclesById.put(vehicle.getVehicleId(), vehicle));

        // 4) enlazar a cada conductor con su vehículo actual, si fue importado.
        int linked = 0;
        for (Map.Entry<String, Map<String, Object>> entry : driverDocs.entrySet()) {
            String vehicleId = FirestoreProfileMapper.nullableText(entry.getValue(), "vehicleId");
            if (vehicleId == null) {
                continue;
            }
            if (vehiclesById.containsKey(vehicleId)) {
                savedDrivers.get(savedUsers.get(entry.getKey()).getId()).setVehicleId(vehicleId);
                linked++;
            } else {
                warnings.add("conductor " + entry.getKey() + ": su vehicleId '" + vehicleId
                        + "' no se importó, queda sin vehículo");
            }
        }
        drivers.flush();

        // 5) pasajeros: cuelgan de users y no dependen de nada más.
        List<PassengerRow> passengerRows = new ArrayList<>();
        passengerDocs.forEach((uid, data) ->
                passengerRows.add(FirestoreProfileMapper.toPassenger(savedUsers.get(uid).getId(), data, importedAt)));
        passengers.saveAll(passengerRows);
        passengers.flush();

        return new Report(
                savedUsers.size(),
                savedDrivers.size(),
                savedVehicles.size(),
                skipped,
                linked,
                passengerRows.size(),
                warnings);
    }

    // De "uid de Firebase del que actuó" a su users.id. Null si no hay actor o
    // ya no existe (en Postgres la columna admite NULL: "quién" se perdió, pero
    // el hecho de que se aprobó/bloqueó se conserva).
    private UUID resolveActor(String actorFirebaseUid, Map<String, UserRow> importedUsers) {
        if (actorFirebaseUid == null) {
            return null;
        }
        UserRow imported = importedUsers.get(actorFirebaseUid);
        if (imported != null) {
            return imported.getId();
        }
        return users.findByFirebaseUid(actorFirebaseUid).map(UserRow::getId).orElse(null);
    }
}
