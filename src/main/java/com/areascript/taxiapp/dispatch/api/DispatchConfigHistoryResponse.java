package com.areascript.taxiapp.dispatch.api;

import com.areascript.taxiapp.dispatch.application.DispatchConfigService.ChangeView;
import com.areascript.taxiapp.dispatch.domain.DispatchConfigChange;

import java.time.Instant;
import java.util.List;

// Una página del historial de cambios, de más nuevo a más viejo. `nextBeforeId`
// es lo que hay que mandar como `beforeId` para pedir la página siguiente; null
// = no hay más.
//
// `type`: "config" (se editaron anillos o frescura) o "admins_can_edit" (el
// superuser movió el interruptor). Cada cambio trae la foto completa de antes y
// de después.
public record DispatchConfigHistoryResponse(List<Change> changes, Long nextBeforeId) {

    public record Change(
            long id,
            Instant changedAt,
            String changedByName,
            String type,
            Snapshot before,
            Snapshot after
    ) {
    }

    public record Snapshot(
            List<DispatchConfigResponse.Ring> rings,
            int locationMaxAgeSeconds,
            boolean adminsCanEdit
    ) {

        static Snapshot from(DispatchConfigChange.Snapshot snapshot) {
            return new Snapshot(
                    snapshot.rings().stream().map(DispatchConfigResponse.Ring::from).toList(),
                    (int) snapshot.locationMaxAge().toSeconds(),
                    snapshot.adminsCanEdit());
        }
    }

    public static DispatchConfigHistoryResponse from(List<ChangeView> views, int requestedLimit) {
        List<Change> changes = views.stream().map(view -> new Change(
                view.change().id(),
                view.change().changedAt(),
                view.changedByName(),
                view.change().type().wireValue(),
                Snapshot.from(view.change().before()),
                Snapshot.from(view.change().after()))).toList();
        Long next = views.size() == requestedLimit && !views.isEmpty()
                ? views.get(views.size() - 1).change().id()
                : null;
        return new DispatchConfigHistoryResponse(changes, next);
    }
}
