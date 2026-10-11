package com.areascript.taxiapp.bootstrap;

import com.areascript.taxiapp.common.firebase.FirebaseProperties;
import com.areascript.taxiapp.user.infra.DriverRowRepository;
import com.areascript.taxiapp.user.infra.VehicleRowRepository;
import com.areascript.taxiapp.user.infra.PassengerRowRepository;
import com.areascript.taxiapp.user.infra.UserRowRepository;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Copia de UNA SOLA CORRIDA: pasajeros, conductores y vehículos de Firestore a
// Postgres. No corre en un arranque normal: solo si se pide con la propiedad
//
//   java -jar taxiapp.jar --taxiapp.import.firestore-to-postgres=true
//
// Al terminar, el proceso se cierra solo (no levanta el servidor web). Se puede
// repetir cuantas veces haga falta: es idempotente (ver ProfileImportService).
//
// Solo LEE Firestore; lo único que escribe es Postgres.
@Component
@ConditionalOnProperty(name = "taxiapp.import.firestore-to-postgres", havingValue = "true")
public class FirestoreToPostgresImporter implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FirestoreToPostgresImporter.class);

    private final Firestore firestore;
    private final long timeoutMillis;
    private final TransactionTemplate transaction;
    private final ProfileImportService importService;
    private final ConfigurableApplicationContext context;

    public FirestoreToPostgresImporter(
            Firestore firestore,
            FirebaseProperties firebaseProperties,
            TransactionTemplate transaction,
            UserRowRepository users,
            DriverRowRepository drivers,
            VehicleRowRepository vehicles,
            PassengerRowRepository passengers,
            ConfigurableApplicationContext context) {
        this.firestore = firestore;
        this.timeoutMillis = firebaseProperties.operationTimeout().toMillis();
        this.transaction = transaction;
        this.importService = new ProfileImportService(users, drivers, vehicles, passengers);
        this.context = context;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("ImportDebug | Leyendo drivers, vehicles y passengers de Firestore...");

        Map<String, Map<String, Object>> driverDocs = readCollection("drivers");
        Map<String, Map<String, Object>> vehicleDocs = readCollection("vehicles");
        Map<String, Map<String, Object>> passengerDocs = readCollection("passengers");
        log.info("ImportDebug | Leídos: {} conductores, {} vehículos, {} pasajeros",
                driverDocs.size(), vehicleDocs.size(), passengerDocs.size());

        // Todo en una transacción: o se copia completo o no se copia nada.
        ProfileImportService.Report report = transaction.execute(status ->
                importService.importProfiles(driverDocs, vehicleDocs, passengerDocs, Instant.now()));

        log.info("ImportDebug | Importación completa: {} usuarios, {} conductores, {} vehículos ({} omitidos), "
                        + "{} conductores con vehículo enlazado, {} pasajeros",
                report.users(), report.drivers(), report.vehicles(), report.vehiclesSkipped(),
                report.driversLinkedToVehicle(), report.passengers());
        report.warnings().forEach(warning -> log.warn("ImportDebug | {}", warning));

        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private Map<String, Map<String, Object>> readCollection(String name) throws Exception {
        List<QueryDocumentSnapshot> documents = firestore.collection(name).get()
                .get(timeoutMillis, TimeUnit.MILLISECONDS).getDocuments();
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (QueryDocumentSnapshot document : documents) {
            byId.put(document.getId(), document.getData());
        }
        return byId;
    }
}
