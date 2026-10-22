package com.areascript.taxiapp.characterization.support;

import com.areascript.taxiapp.common.error.ConflictException;
import com.areascript.taxiapp.dispatch.domain.DispatchConfig;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigChange;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigChange.Snapshot;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigStore;
import com.areascript.taxiapp.dispatch.domain.DispatchPlan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// La configuración editable del despacho y su historial en memoria: hace el
// papel de JpaDispatchConfigStore en los tests que corren sin Postgres (los
// HTTP y los de aplicación). Reproduce lo que importa del real: versión que
// sube con cada edición, conflicto si la versión no es la vigente, "sin
// cambios" que no deja rastro, y el interruptor que no toca la versión.
public class FakeDispatchConfigStore implements DispatchConfigStore {

    private DispatchConfig config = initial();
    private final List<DispatchConfigChange> changes = new ArrayList<>();
    private long nextChangeId = 1;

    private static DispatchConfig initial() {
        return new DispatchConfig(FakeDispatchPlans.standard(), false, 0, Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Override
    public synchronized DispatchConfig load() {
        return config;
    }

    @Override
    public synchronized DispatchConfig replace(DispatchPlan plan, long expectedVersion, UUID editorId) {
        if (config.version() != expectedVersion) {
            throw new ConflictException("La configuración cambió mientras la editabas");
        }
        if (config.plan().rings().equals(plan.rings()) && config.plan().locationMaxAge().equals(plan.locationMaxAge())) {
            return config;
        }
        DispatchConfig before = config;
        config = new DispatchConfig(plan, before.adminsCanEdit(), before.version() + 1, Instant.now(), editorId);
        record(DispatchConfigChange.Type.CONFIG, editorId, before, config);
        return config;
    }

    @Override
    public synchronized DispatchConfig setAdminsCanEdit(boolean enabled, UUID editorId) {
        if (config.adminsCanEdit() == enabled) {
            return config;
        }
        DispatchConfig before = config;
        config = new DispatchConfig(before.plan(), enabled, before.version(), before.updatedAt(), before.updatedBy());
        record(DispatchConfigChange.Type.ADMINS_CAN_EDIT, editorId, before, config);
        return config;
    }

    @Override
    public synchronized List<DispatchConfigChange> history(int limit, Long beforeId) {
        return changes.stream()
                .filter(change -> beforeId == null || change.id() < beforeId)
                .sorted((a, b) -> Long.compare(b.id(), a.id()))
                .limit(limit)
                .toList();
    }

    private void record(DispatchConfigChange.Type type, UUID editorId, DispatchConfig before, DispatchConfig after) {
        changes.add(new DispatchConfigChange(
                nextChangeId++, Instant.now(), editorId, type, Snapshot.of(before), Snapshot.of(after)));
    }

    // ---- Para el arnés ----

    public synchronized void clear() {
        config = initial();
        changes.clear();
        nextChangeId = 1;
    }

    public synchronized void seedAdminsCanEdit(boolean enabled) {
        config = new DispatchConfig(config.plan(), enabled, config.version(), config.updatedAt(), config.updatedBy());
    }
}
