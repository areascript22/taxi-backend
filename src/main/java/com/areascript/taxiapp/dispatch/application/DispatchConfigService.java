package com.areascript.taxiapp.dispatch.application;

import com.areascript.taxiapp.common.config.DispatchProperties;
import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.common.error.ForbiddenException;
import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.dispatch.domain.DispatchConfig;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigChange;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigLimits;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigPermissions;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigRules;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigStore;
import com.areascript.taxiapp.dispatch.domain.DispatchPlan;
import com.areascript.taxiapp.dispatch.domain.DispatchRing;
import com.areascript.taxiapp.user.domain.UserAccount;
import com.areascript.taxiapp.user.domain.UserAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Casos de uso del panel de configuración del despacho (la app del conductor,
// pantalla de administración). Aplica quién puede ver y quién puede editar y
// valida lo que se guarda; el almacenamiento es el puerto DispatchConfigStore.
//
// Quién edita: el superuser siempre; un admin solo si el superuser encendió
// "los admins pueden editar". El interruptor lo mueve solo el superuser. Cada
// edición pregunta eso primero (ver DispatchConfigPermissions).
@Service
public class DispatchConfigService {

    private static final Logger log = LoggerFactory.getLogger(DispatchConfigService.class);

    private static final int MIN_HISTORY_LIMIT = 1;
    private static final int MAX_HISTORY_LIMIT = 50;

    private final DispatchConfigStore store;
    private final UserAccounts users;
    private final DispatchConfigLimits limits;
    private final Duration configuredRequestExpiry;

    public DispatchConfigService(
            DispatchConfigStore store,
            UserAccounts users,
            DispatchProperties dispatchProperties,
            TaxiappProperties properties
    ) {
        this.store = store;
        this.users = users;
        this.limits = new DispatchConfigLimits(
                dispatchProperties.maxRings(),
                dispatchProperties.maxTotalWait(),
                dispatchProperties.minLocationMaxAge());
        this.configuredRequestExpiry = properties.rides().pendingRequestExpiry();
    }

    // Lo que el panel muestra: la configuración, los topes y lo que el
    // llamador puede hacer con ella.
    public record ConfigView(
            DispatchConfig config,
            String updatedByName,
            Duration totalWait,
            Duration requestExpiry,
            DispatchConfigLimits limits,
            boolean canEdit,
            boolean canChangeAdminEditing
    ) {
    }

    // Un anillo tal como lo pide el panel. El número no se manda: lo da la
    // posición (el primero es el anillo 1).
    public record RingInput(int radiusMeters, int waitSeconds, int maxDrivers) {
    }

    public record UpdateCommand(List<RingInput> rings, int locationMaxAgeSeconds, long expectedVersion) {
    }

    public record ChangeView(DispatchConfigChange change, String changedByName) {
    }

    public ConfigView get(boolean callerIsSuperUser) {
        return view(store.load(), callerIsSuperUser);
    }

    public ConfigView update(UpdateCommand command, String callerUid, boolean callerIsSuperUser) {
        // Se pregunta la configuración vigente ANTES de validar el contenido: un
        // admin sin permiso no tiene por qué enterarse de qué está mal en lo que
        // mandó, y no debe poder ni intentarlo.
        DispatchConfig current = store.load();
        if (!DispatchConfigPermissions.canEdit(callerIsSuperUser, current.adminsCanEdit())) {
            throw new ForbiddenException("La edición de la configuración del despacho está reservada al superuser");
        }

        DispatchPlan plan = DispatchConfigRules.buildPlan(
                toRings(command.rings()),
                Duration.ofSeconds(command.locationMaxAgeSeconds()),
                limits);

        DispatchConfig saved = store.replace(plan, command.expectedVersion(), editorId(callerUid));
        if (saved.version() != current.version()) {
            log.info("DispatchDebug | Configuración del despacho editada por uid={}: {} anillos, versión {}",
                    callerUid, plan.rings().size(), saved.version());
        }
        return view(saved, callerIsSuperUser);
    }

    public ConfigView setAdminsCanEdit(boolean enabled, String callerUid, boolean callerIsSuperUser) {
        if (!DispatchConfigPermissions.canChangeAdminEditing(callerIsSuperUser)) {
            throw new ForbiddenException("Solo el superuser puede cambiar si los admins pueden editar el despacho");
        }
        DispatchConfig saved = store.setAdminsCanEdit(enabled, editorId(callerUid));
        log.info("DispatchDebug | uid={} dejó adminsCanEdit={}", callerUid, saved.adminsCanEdit());
        return view(saved, callerIsSuperUser);
    }

    public List<ChangeView> history(int limit, Long beforeId) {
        if (limit < MIN_HISTORY_LIMIT || limit > MAX_HISTORY_LIMIT) {
            throw new InvalidInputException(
                    "limit debe estar entre " + MIN_HISTORY_LIMIT + " y " + MAX_HISTORY_LIMIT);
        }
        List<DispatchConfigChange> changes = store.history(limit, beforeId);
        Map<UUID, String> names = new HashMap<>();
        List<ChangeView> views = new ArrayList<>();
        for (DispatchConfigChange change : changes) {
            views.add(new ChangeView(change, nameOf(change.changedBy(), names)));
        }
        return views;
    }

    // ---- Internos ----

    private ConfigView view(DispatchConfig config, boolean callerIsSuperUser) {
        DispatchPlan plan = config.plan();
        return new ConfigView(
                config,
                nameOf(config.updatedBy(), new HashMap<>()),
                plan.totalWait(),
                plan.requestExpiry(configuredRequestExpiry),
                limits,
                DispatchConfigPermissions.canEdit(callerIsSuperUser, config.adminsCanEdit()),
                DispatchConfigPermissions.canChangeAdminEditing(callerIsSuperUser));
    }

    private static List<DispatchRing> toRings(List<RingInput> inputs) {
        if (inputs == null) {
            throw new InvalidInputException("Faltan los anillos");
        }
        List<DispatchRing> rings = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            RingInput input = inputs.get(i);
            rings.add(new DispatchRing(
                    i + 1,
                    input.radiusMeters(),
                    Duration.ofSeconds(input.waitSeconds()),
                    input.maxDrivers()));
        }
        return rings;
    }

    // users.id de quien edita. El filtro de seguridad ya aprovisionó a la
    // persona, así que existe; si no (carrera con un borrado), el cambio queda
    // sin autor en vez de fallar.
    private UUID editorId(String firebaseUid) {
        return users.find(firebaseUid).map(UserAccount::id).orElse(null);
    }

    // Nombre para mostrar de quien hizo un cambio; "" si no se sabe (valores
    // iniciales o persona borrada). `cache` evita consultar dos veces a la misma
    // persona dentro de una respuesta.
    private String nameOf(UUID userId, Map<UUID, String> cache) {
        if (userId == null) {
            return "";
        }
        return cache.computeIfAbsent(userId, id -> users.findById(id)
                .map(account -> account.displayName().isBlank() ? account.email() : account.displayName())
                .orElse(""));
    }
}
