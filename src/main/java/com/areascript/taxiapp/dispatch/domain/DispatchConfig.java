package com.areascript.taxiapp.dispatch.domain;

import java.time.Instant;
import java.util.UUID;

// La configuración del despacho tal como la ve el panel de administración: el
// plan (anillos y frescura de la ubicación) más lo que hace falta para editarlo
// de forma segura.
//
//   adminsCanEdit  Interruptor que solo mueve el superuser: si está apagado, solo
//                  el superuser edita; si está encendido, los admins también.
//   version        Contador que sube con cada edición. Quien edita manda la que
//                  vio; si ya cambió, hay conflicto (ver DispatchConfigStore).
//   updatedAt /    Cuándo y quién editó por última vez (null = valores
//   updatedBy      iniciales). Quién = users.id; el nombre lo resuelve la capa de
//                  aplicación.
public record DispatchConfig(
        DispatchPlan plan,
        boolean adminsCanEdit,
        long version,
        Instant updatedAt,
        UUID updatedBy
) {
}
