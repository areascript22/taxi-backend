package com.areascript.taxiapp.bootstrap;

import com.areascript.taxiapp.driveradmin.infra.DriverRow;
import com.areascript.taxiapp.driveradmin.infra.DriverRowRepository;
import com.areascript.taxiapp.driveradmin.infra.VehicleRow;
import com.areascript.taxiapp.driveradmin.infra.VehicleRowRepository;
import com.areascript.taxiapp.passenger.infra.PassengerRow;
import com.areascript.taxiapp.passenger.infra.PassengerRowRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Copia los perfiles (ya leídos de Firestore como mapas) a Postgres.
//
// Es IDEMPOTENTE: se puede correr varias veces y cada fila se sobrescribe con
// lo que haya en Firestore en ese momento (upsert por uid / vehicleId). No
// borra nada: un conductor que ya no está en Firestore se queda en Postgres.
//
// No abre su propia transacción: el que lo invoca decide el alcance (el
// importador de arranque lo envuelve todo en una, para que un fallo a medias no
// deje la copia a la mitad).
class ProfileImportService {

    private final DriverRowRepository drivers;
    private final VehicleRowRepository vehicles;
    private final PassengerRowRepository passengers;

    ProfileImportService(
            DriverRowRepository drivers,
            VehicleRowRepository vehicles,
            PassengerRowRepository passengers) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.passengers = passengers;
    }

    record Report(
            int drivers,
            int vehicles,
            int vehiclesSkipped,
            int driversLinkedToVehicle,
            int passengers,
            List<String> warnings) {
    }

    // Los tres argumentos son "id del documento -> sus campos".
    Report importProfiles(
            Map<String, Map<String, Object>> driverDocs,
            Map<String, Map<String, Object>> vehicleDocs,
            Map<String, Map<String, Object>> passengerDocs,
            Instant importedAt) {

        List<String> warnings = new ArrayList<>();

        // 1) Conductores, todavía sin vehículo: la clave foránea
        //    drivers.vehicle_id -> vehicles exige que el vehículo ya exista.
        List<DriverRow> driverRows = new ArrayList<>();
        driverDocs.forEach((uid, data) -> driverRows.add(FirestoreProfileMapper.toDriver(uid, data, importedAt)));
        Map<String, DriverRow> savedDrivers = new HashMap<>();
        for (DriverRow saved : drivers.saveAll(driverRows)) {
            savedDrivers.put(saved.getUid(), saved);
        }
        drivers.flush();

        // 2) Vehículos. Uno sin dueño, o cuyo dueño no existe, no se puede
        //    guardar: se salta y se avisa (no es un error de la importación).
        List<VehicleRow> vehicleRows = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<String, Map<String, Object>> entry : vehicleDocs.entrySet()) {
            VehicleRow row = FirestoreProfileMapper.toVehicle(entry.getKey(), entry.getValue(), importedAt);
            if (row == null) {
                warnings.add("vehículo " + entry.getKey() + ": no tiene driverId, se omite");
                skipped++;
            } else if (!savedDrivers.containsKey(row.getDriverUid())) {
                warnings.add("vehículo " + entry.getKey() + ": su conductor '" + row.getDriverUid()
                        + "' no existe en drivers, se omite");
                skipped++;
            } else {
                vehicleRows.add(row);
            }
        }
        List<VehicleRow> savedVehicles = vehicles.saveAll(vehicleRows);
        vehicles.flush();
        Map<String, VehicleRow> vehiclesById = new HashMap<>();
        savedVehicles.forEach(vehicle -> vehiclesById.put(vehicle.getVehicleId(), vehicle));

        // 3) Enlazar a cada conductor con su vehículo actual, si fue importado.
        int linked = 0;
        for (Map.Entry<String, Map<String, Object>> entry : driverDocs.entrySet()) {
            Object vehicleId = entry.getValue().get("vehicleId");
            if (vehicleId == null || vehicleId.toString().isBlank()) {
                continue;
            }
            if (vehiclesById.containsKey(vehicleId.toString())) {
                savedDrivers.get(entry.getKey()).setVehicleId(vehicleId.toString());
                linked++;
            } else {
                warnings.add("conductor " + entry.getKey() + ": su vehicleId '" + vehicleId
                        + "' no se importó, queda sin vehículo");
            }
        }
        drivers.flush();

        // 4) Pasajeros: no dependen de nada.
        List<PassengerRow> passengerRows = new ArrayList<>();
        passengerDocs.forEach((uid, data) -> passengerRows.add(FirestoreProfileMapper.toPassenger(uid, data, importedAt)));
        passengers.saveAll(passengerRows);
        passengers.flush();

        return new Report(
                savedDrivers.size(),
                savedVehicles.size(),
                skipped,
                linked,
                passengerRows.size(),
                warnings);
    }
}
