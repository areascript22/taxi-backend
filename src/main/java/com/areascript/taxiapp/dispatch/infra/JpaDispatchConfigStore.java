package com.areascript.taxiapp.dispatch.infra;

import com.areascript.taxiapp.common.error.ConflictException;
import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.dispatch.domain.DispatchConfig;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigChange;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigChange.Snapshot;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigStore;
import com.areascript.taxiapp.dispatch.domain.DispatchPlan;
import com.areascript.taxiapp.dispatch.domain.DispatchRing;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// La edición de la configuración del despacho y su historial, en Postgres.
//
// Las lecturas del despacho (JpaDispatchPlans) no pasan por acá: esa es la
// ruta caliente de cada solicitud. Esta clase es el camino del panel de
// administración.
//
// Un guardado es UNA transacción: bloquea la fila de configuración, compara la
// versión, reemplaza los anillos y deja la entrada del historial. Si algo falla,
// no queda nada a medias (ni una configuración sin historial, ni al revés).
//
// Condicional a taxiapp.persistence.postgres, igual que los demás adaptadores de
// Postgres: los tests HTTP la reemplazan por una en memoria.
@Component
@ConditionalOnProperty(name = "taxiapp.persistence.postgres", havingValue = "true", matchIfMissing = true)
public class JpaDispatchConfigStore implements DispatchConfigStore {

    private static final Logger log = LoggerFactory.getLogger(JpaDispatchConfigStore.class);

    private static final String BEFORE = "before";
    private static final String AFTER = "after";

    private final DispatchRingRowRepository rings;
    private final DispatchSettingsRowRepository settings;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public JpaDispatchConfigStore(
            DispatchRingRowRepository rings,
            DispatchSettingsRowRepository settings,
            JdbcTemplate jdbc,
            EntityManager entityManager
    ) {
        this.rings = rings;
        this.settings = settings;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public DispatchConfig load() {
        try {
            DispatchSettingsRow row = settings.findById(DispatchSettingsRow.SINGLE_ROW_ID).orElseThrow();
            return toConfig(row, rings.findAllByOrderByRingNumberAsc());
        } catch (DataAccessException e) {
            log.error("DispatchDebug | No se pudo leer la configuración del despacho: {}", e.getMessage(), e);
            throw new ExternalServiceException("No se pudo leer la configuración del despacho", e);
        }
    }

    @Override
    @Transactional
    public DispatchConfig replace(DispatchPlan plan, long expectedVersion, UUID editorId) {
        try {
            DispatchSettingsRow row = settings.findSingleForUpdate().orElseThrow();
            if (row.getVersion() != expectedVersion) {
                throw new ConflictException(
                        "La configuración cambió mientras la editabas. Recárgala y vuelve a aplicar tus cambios");
            }

            List<DispatchRingRow> currentRings = rings.findAllByOrderByRingNumberAsc();
            DispatchConfig before = toConfig(row, currentRings);
            if (sameContent(before.plan(), plan)) {
                return before;
            }

            // uq_dispatch_rings_radius no es diferible: actualizar fila por fila
            // chocaría a mitad de camino (por ejemplo al intercambiar dos radios).
            // Se borra todo, se aplica el borrado y se inserta lo nuevo.
            rings.deleteAllInBatch();
            entityManager.flush();
            // El borrado en lote no avisa al contexto de persistencia: las filas
            // que se leyeron arriba seguirían "vivas" y guardar un anillo con el
            // mismo número las actualizaría (sin insertar nada) en vez de crear
            // la nueva. Hay que soltarlas, y volver a pedir la fila de ajustes.
            entityManager.clear();
            row = settings.findById(DispatchSettingsRow.SINGLE_ROW_ID).orElseThrow();
            Instant now = Instant.now();
            List<DispatchRingRow> inserted = new ArrayList<>();
            for (DispatchRing ring : plan.rings()) {
                DispatchRingRow newRow = new DispatchRingRow(
                        ring.number(), ring.radiusMeters(), (int) ring.waitTime().toSeconds(), ring.maxDrivers());
                newRow.setUpdatedAt(now);
                newRow.setUpdatedBy(editorId);
                inserted.add(newRow);
            }
            rings.saveAll(inserted);

            row.setLocationMaxAgeSeconds((int) plan.locationMaxAge().toSeconds());
            row.setVersion(row.getVersion() + 1);
            row.setUpdatedAt(now);
            row.setUpdatedBy(editorId);
            settings.saveAndFlush(row);

            DispatchConfig after = toConfig(row, inserted);
            recordChange(DispatchConfigChange.Type.CONFIG, editorId, now, before, after);
            return after;
        } catch (DataAccessException e) {
            log.error("DispatchDebug | No se pudo guardar la configuración del despacho: {}", e.getMessage(), e);
            throw new ExternalServiceException("No se pudo guardar la configuración del despacho", e);
        }
    }

    @Override
    @Transactional
    public DispatchConfig setAdminsCanEdit(boolean enabled, UUID editorId) {
        try {
            DispatchSettingsRow row = settings.findSingleForUpdate().orElseThrow();
            List<DispatchRingRow> currentRings = rings.findAllByOrderByRingNumberAsc();
            DispatchConfig before = toConfig(row, currentRings);
            if (row.isAdminsCanEdit() == enabled) {
                return before;
            }

            // No toca version/updated_at/updated_by: esos describen la última
            // edición de los anillos y la frescura, y mover este interruptor no
            // debe invalidar a quien está editando en ese momento.
            row.setAdminsCanEdit(enabled);
            settings.saveAndFlush(row);

            DispatchConfig after = toConfig(row, currentRings);
            recordChange(DispatchConfigChange.Type.ADMINS_CAN_EDIT, editorId, Instant.now(), before, after);
            return after;
        } catch (DataAccessException e) {
            log.error("DispatchDebug | No se pudo cambiar adminsCanEdit: {}", e.getMessage(), e);
            throw new ExternalServiceException("No se pudo guardar la configuración del despacho", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DispatchConfigChange> history(int limit, Long beforeId) {
        try {
            String select = "SELECT id, changed_at, changed_by, change_type, "
                    + "before_location_max_age_seconds, before_admins_can_edit, "
                    + "after_location_max_age_seconds, after_admins_can_edit "
                    + "FROM dispatch_config_changes ";
            List<ChangeRow> changes = beforeId == null
                    ? jdbc.query(select + "ORDER BY id DESC LIMIT ?", JpaDispatchConfigStore::mapChange, limit)
                    : jdbc.query(select + "WHERE id < ? ORDER BY id DESC LIMIT ?",
                            JpaDispatchConfigStore::mapChange, beforeId, limit);
            if (changes.isEmpty()) {
                return List.of();
            }

            // Los anillos de todas las fotos de la página, en una sola consulta.
            Map<String, List<DispatchRing>> ringsByChangeAndSide = new HashMap<>();
            String ids = String.join(",", changes.stream().map(change -> Long.toString(change.id())).toList());
            jdbc.query("SELECT change_id, side, ring_number, radius_meters, wait_seconds, max_drivers "
                    + "FROM dispatch_config_change_rings WHERE change_id IN (" + ids + ") "
                    + "ORDER BY change_id, side, ring_number", resultSet -> {
                String key = resultSet.getLong("change_id") + ":" + resultSet.getString("side");
                ringsByChangeAndSide.computeIfAbsent(key, k -> new ArrayList<>()).add(new DispatchRing(
                        resultSet.getInt("ring_number"),
                        resultSet.getInt("radius_meters"),
                        Duration.ofSeconds(resultSet.getInt("wait_seconds")),
                        resultSet.getInt("max_drivers")));
            });

            List<DispatchConfigChange> result = new ArrayList<>();
            for (ChangeRow change : changes) {
                result.add(new DispatchConfigChange(
                        change.id(),
                        change.changedAt(),
                        change.changedBy(),
                        change.type(),
                        new Snapshot(
                                ringsByChangeAndSide.getOrDefault(change.id() + ":" + BEFORE, List.of()),
                                Duration.ofSeconds(change.beforeMaxAgeSeconds()),
                                change.beforeAdminsCanEdit()),
                        new Snapshot(
                                ringsByChangeAndSide.getOrDefault(change.id() + ":" + AFTER, List.of()),
                                Duration.ofSeconds(change.afterMaxAgeSeconds()),
                                change.afterAdminsCanEdit())));
            }
            return result;
        } catch (DataAccessException e) {
            log.error("DispatchDebug | No se pudo leer el historial del despacho: {}", e.getMessage(), e);
            throw new ExternalServiceException("No se pudo leer el historial del despacho", e);
        }
    }

    // ---- Internos ----

    private void recordChange(
            DispatchConfigChange.Type type,
            UUID editorId,
            Instant at,
            DispatchConfig before,
            DispatchConfig after
    ) {
        Long changeId = jdbc.queryForObject(
                "INSERT INTO dispatch_config_changes (changed_at, changed_by, change_type, "
                        + "before_location_max_age_seconds, before_admins_can_edit, "
                        + "after_location_max_age_seconds, after_admins_can_edit) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                java.sql.Timestamp.from(at),
                editorId,
                type.wireValue(),
                (int) before.plan().locationMaxAge().toSeconds(),
                before.adminsCanEdit(),
                (int) after.plan().locationMaxAge().toSeconds(),
                after.adminsCanEdit());
        insertRings(changeId, BEFORE, before.plan().rings());
        insertRings(changeId, AFTER, after.plan().rings());
    }

    private void insertRings(long changeId, String side, List<DispatchRing> ringList) {
        for (DispatchRing ring : ringList) {
            jdbc.update("INSERT INTO dispatch_config_change_rings "
                            + "(change_id, side, ring_number, radius_meters, wait_seconds, max_drivers) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    changeId, side, ring.number(), ring.radiusMeters(), (int) ring.waitTime().toSeconds(),
                    ring.maxDrivers());
        }
    }

    private static boolean sameContent(DispatchPlan current, DispatchPlan proposed) {
        return current.rings().equals(proposed.rings())
                && current.locationMaxAge().equals(proposed.locationMaxAge());
    }

    private static DispatchConfig toConfig(DispatchSettingsRow row, List<DispatchRingRow> ringRows) {
        List<DispatchRing> ringList = ringRows.stream()
                .map(r -> new DispatchRing(
                        r.getRingNumber(),
                        r.getRadiusMeters(),
                        Duration.ofSeconds(r.getWaitSeconds()),
                        r.getMaxDrivers()))
                .toList();
        // Si lo guardado quedó inválido por fuera de este camino (editado a mano
        // en la base), el constructor del plan lanza: un 500 con su causa en el
        // log, que se arregla en la base. No se esconde con un plan vacío.
        DispatchPlan plan = new DispatchPlan(ringList, Duration.ofSeconds(row.getLocationMaxAgeSeconds()));
        return new DispatchConfig(plan, row.isAdminsCanEdit(), row.getVersion(), row.getUpdatedAt(), row.getUpdatedBy());
    }

    private record ChangeRow(
            long id,
            Instant changedAt,
            UUID changedBy,
            DispatchConfigChange.Type type,
            int beforeMaxAgeSeconds,
            boolean beforeAdminsCanEdit,
            int afterMaxAgeSeconds,
            boolean afterAdminsCanEdit
    ) {
    }

    private static ChangeRow mapChange(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChangeRow(
                rs.getLong("id"),
                rs.getTimestamp("changed_at").toInstant(),
                rs.getObject("changed_by", UUID.class),
                DispatchConfigChange.Type.fromStored(rs.getString("change_type")),
                rs.getInt("before_location_max_age_seconds"),
                rs.getBoolean("before_admins_can_edit"),
                rs.getInt("after_location_max_age_seconds"),
                rs.getBoolean("after_admins_can_edit"));
    }
}
