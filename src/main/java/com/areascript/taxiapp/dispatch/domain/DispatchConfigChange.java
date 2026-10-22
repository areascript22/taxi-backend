package com.areascript.taxiapp.dispatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Una entrada del historial de cambios de la configuración del despacho: quién,
// cuándo, qué tipo de cambio y cómo estaba la configuración antes y después.
//
// Guarda la FOTO completa de cada lado en lugar de un diff: se lee sin
// reconstruir nada y no depende de cómo se numeren los anillos (borrar el anillo
// 2 renumera el 3).
public record DispatchConfigChange(
        long id,
        Instant changedAt,
        UUID changedBy,
        Type type,
        Snapshot before,
        Snapshot after
) {

    public enum Type {
        // Se editaron los anillos o la frescura de la ubicación.
        CONFIG("config"),
        // El superuser movió el interruptor "los admins pueden editar".
        ADMINS_CAN_EDIT("admins_can_edit");

        private final String wireValue;

        Type(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static Type fromStored(String value) {
            for (Type type : values()) {
                if (type.wireValue.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Tipo de cambio desconocido: " + value);
        }
    }

    // La configuración en un instante. No valida (a diferencia de DispatchPlan):
    // es un registro de lo que hubo, no una configuración que se vaya a usar.
    public record Snapshot(List<DispatchRing> rings, Duration locationMaxAge, boolean adminsCanEdit) {

        public Snapshot {
            rings = List.copyOf(rings);
        }

        public static Snapshot of(DispatchConfig config) {
            return new Snapshot(config.plan().rings(), config.plan().locationMaxAge(), config.adminsCanEdit());
        }
    }
}
