package com.areascript.taxiapp.dispatch.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.dispatch.domain.DispatchPlan;
import com.areascript.taxiapp.dispatch.domain.DispatchPlans;
import com.areascript.taxiapp.dispatch.domain.DispatchRing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

// La configuración del despacho leída de dispatch_rings y dispatch_settings en
// cada búsqueda (son dos consultas de pocas filas): así un cambio del
// administrador vale en la siguiente solicitud, sin reiniciar nada.
//
// Condicional a taxiapp.persistence.postgres, igual que los demás adaptadores de
// Postgres: los tests HTTP la reemplazan por una en memoria.
@Component
@ConditionalOnProperty(name = "taxiapp.persistence.postgres", havingValue = "true", matchIfMissing = true)
public class JpaDispatchPlans implements DispatchPlans {

    private static final Logger log = LoggerFactory.getLogger(JpaDispatchPlans.class);

    private final DispatchRingRowRepository rings;
    private final DispatchSettingsRowRepository settings;

    public JpaDispatchPlans(DispatchRingRowRepository rings, DispatchSettingsRowRepository settings) {
        this.rings = rings;
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public DispatchPlan current() {
        try {
            List<DispatchRing> ringList = rings.findAllByOrderByRingNumberAsc().stream()
                    .map(row -> new DispatchRing(
                            row.getRingNumber(),
                            row.getRadiusMeters(),
                            Duration.ofSeconds(row.getWaitSeconds()),
                            row.getMaxDrivers()))
                    .toList();
            Duration maxAge = settings.findById(DispatchSettingsRow.SINGLE_ROW_ID)
                    .map(row -> Duration.ofSeconds(row.getLocationMaxAgeSeconds()))
                    .orElse(DispatchPlan.empty().locationMaxAge());

            try {
                return new DispatchPlan(ringList, maxAge);
            } catch (IllegalArgumentException invalid) {
                // Lo que no pudo impedir la base (radios que no crecen, números
                // salteados). Mejor no despachar que despachar mal.
                log.error("DispatchDebug | La configuración de anillos es inválida, no se despacha: {}",
                        invalid.getMessage());
                return DispatchPlan.empty();
            }
        } catch (DataAccessException e) {
            log.error("DispatchDebug | No se pudo leer la configuración del despacho: {}", e.getMessage(), e);
            throw new ExternalServiceException("No se pudo leer la configuración del despacho", e);
        }
    }
}
