package com.areascript.taxiapp.dispatch.api;

import com.areascript.taxiapp.dispatch.application.DispatchConfigService.ConfigView;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigLimits;
import com.areascript.taxiapp.dispatch.domain.DispatchRing;

import java.time.Instant;
import java.util.List;

// La configuración del despacho para el panel de administración. Los nombres de
// los campos son el contrato con driver_app: no se cambian sin cambiar la app.
//
// Además de los valores, lleva lo que la pantalla necesita para no adivinar:
//   canEdit / canChangeAdminEditing  qué puede hacer QUIEN pregunta (el botón
//                                    Guardar y el interruptor dependen de esto)
//   totalWaitSeconds / requestExpirySeconds  lo que esos valores implican para
//                                    el pasajero
//   limits                           los topes, para validar el formulario con
//                                    los mismos números que el servidor
public record DispatchConfigResponse(
        List<Ring> rings,
        int locationMaxAgeSeconds,
        boolean adminsCanEdit,
        long version,
        Instant updatedAt,
        String updatedByName,
        int totalWaitSeconds,
        int requestExpirySeconds,
        boolean canEdit,
        boolean canChangeAdminEditing,
        Limits limits
) {

    public record Ring(int number, int radiusMeters, int waitSeconds, int maxDrivers) {

        static Ring from(DispatchRing ring) {
            return new Ring(ring.number(), ring.radiusMeters(), (int) ring.waitTime().toSeconds(), ring.maxDrivers());
        }
    }

    public record Limits(
            int maxRings,
            int maxTotalWaitSeconds,
            int minLocationMaxAgeSeconds,
            int maxLocationMaxAgeSeconds,
            int minRadiusMeters,
            int maxRadiusMeters,
            int minWaitSeconds,
            int maxWaitSeconds,
            int minMaxDrivers,
            int maxMaxDrivers
    ) {

        static Limits from(DispatchConfigLimits limits) {
            return new Limits(
                    limits.maxRings(),
                    (int) limits.maxTotalWait().toSeconds(),
                    (int) limits.minLocationMaxAge().toSeconds(),
                    DispatchConfigLimits.MAX_LOCATION_MAX_AGE_SECONDS,
                    DispatchConfigLimits.MIN_RADIUS_METERS,
                    DispatchConfigLimits.MAX_RADIUS_METERS,
                    DispatchConfigLimits.MIN_WAIT_SECONDS,
                    DispatchConfigLimits.MAX_WAIT_SECONDS,
                    DispatchConfigLimits.MIN_MAX_DRIVERS,
                    DispatchConfigLimits.MAX_MAX_DRIVERS);
        }
    }

    public static DispatchConfigResponse from(ConfigView view) {
        var config = view.config();
        return new DispatchConfigResponse(
                config.plan().rings().stream().map(Ring::from).toList(),
                (int) config.plan().locationMaxAge().toSeconds(),
                config.adminsCanEdit(),
                config.version(),
                config.updatedAt(),
                view.updatedByName(),
                (int) view.totalWait().toSeconds(),
                (int) view.requestExpiry().toSeconds(),
                view.canEdit(),
                view.canChangeAdminEditing(),
                Limits.from(view.limits()));
    }
}
