package com.areascript.taxiapp.driveradmin.domain;

import java.util.Optional;

// Estado de aprobación de un conductor por parte de un admin.
public enum ApprovalStatus {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String wireValue;

    ApprovalStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    // Cómo se escribe en la API y en Firestore.
    public String wireValue() {
        return wireValue;
    }

    // Para validar lo que manda un cliente: vacío si no es un estado conocido.
    public static Optional<ApprovalStatus> parse(String value) {
        for (ApprovalStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    // Para lo que ya está guardado:
    //   - sin el campo -> APPROVED: es un conductor anterior al sistema de
    //     aprobación (mismo criterio que ApprovalStatusBackfill);
    //   - un valor desconocido -> PENDING: ante la duda NO se considera
    //     aprobado, que es lo que ya hacían todas las comprobaciones
    //     ("!= approved").
    public static ApprovalStatus fromStored(Object stored) {
        if (stored == null) {
            return APPROVED;
        }
        return parse(stored.toString()).orElse(PENDING);
    }
}
