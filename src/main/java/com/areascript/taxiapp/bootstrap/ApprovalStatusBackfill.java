package com.areascript.taxiapp.bootstrap;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// Backfill de UNA SOLA CORRIDA: marca approvalStatus=approved/isBlocked=false
// en todo conductor que ya existía antes de introducir el sistema de
// aprobación (ver DriverAdminService.updateApprovalStatus/updateBlockStatus).
// No corre en cada arranque normal del server -- solo si se invoca el jar
// con el argumento --backfill-approval-status:
//
//   java -jar taxiapp.jar --backfill-approval-status
//
// Al terminar, el proceso se cierra solo (no levanta el servidor web).
// Después de correrlo una vez contra producción, esta clase puede borrarse.
//
// Nota: WriteBatch de Firestore soporta hasta 500 operaciones -- si la
// colección 'drivers' llega a superar ese tamaño antes de correr esto,
// habría que partirlo en lotes.
@Component
public class ApprovalStatusBackfill implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ApprovalStatusBackfill.class);
    private static final String DRIVERS_COLLECTION = "drivers";
    private static final String FLAG = "--backfill-approval-status";

    private final Firestore firestore;

    public ApprovalStatusBackfill(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void run(String... args) throws Exception {
        boolean shouldRun = false;
        for (String arg : args) {
            if (FLAG.equals(arg)) {
                shouldRun = true;
                break;
            }
        }
        if (!shouldRun) {
            return;
        }

        log.info("ApprovalStatusBackfillDebug | Iniciando backfill de approvalStatus/isBlocked...");

        List<QueryDocumentSnapshot> documents = firestore.collection(DRIVERS_COLLECTION).get().get().getDocuments();
        WriteBatch batch = firestore.batch();
        int updated = 0;

        for (QueryDocumentSnapshot document : documents) {
            Map<String, Object> data = document.getData();
            boolean needsUpdate = false;

            if (!data.containsKey("approvalStatus")) {
                batch.update(document.getReference(), "approvalStatus", "approved");
                needsUpdate = true;
            }
            if (!data.containsKey("isBlocked")) {
                batch.update(document.getReference(), "isBlocked", false);
                needsUpdate = true;
            }
            if (needsUpdate) {
                updated++;
            }
        }

        if (updated > 0) {
            batch.commit().get();
        }

        log.info(
                "ApprovalStatusBackfillDebug | Backfill completado. {} de {} conductores actualizados.",
                updated,
                documents.size()
        );

        System.exit(0);
    }
}
