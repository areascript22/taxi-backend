package com.areascript.taxiapp.dispatch.domain;

import java.util.List;
import java.util.UUID;

// Puerto de salida: leer y editar la configuración del despacho y su historial.
// (DispatchPlans es la lectura que usa el despacho en cada búsqueda; este es el
// camino del panel de administración.)
//
// Un fallo del almacenamiento se traduce a ExternalServiceException
// (common.error).
public interface DispatchConfigStore {

    // La configuración vigente, con sus datos de edición.
    DispatchConfig load();

    // Reemplaza los anillos y la frescura de la ubicación por los de `plan`, de
    // forma atómica, y deja constancia en el historial.
    //
    // Lanza ConflictException si `expectedVersion` no es la vigente (alguien
    // guardó antes). Si `plan` es idéntico al vigente no cambia nada: ni
    // versión, ni historial.
    //
    // `editorId` es users.id de quien edita.
    DispatchConfig replace(DispatchPlan plan, long expectedVersion, UUID editorId);

    // Enciende o apaga "los admins pueden editar". Si ya tiene ese valor no
    // cambia nada. No toca la versión: no invalida una edición en curso.
    DispatchConfig setAdminsCanEdit(boolean enabled, UUID editorId);

    // Los cambios más recientes primero. `beforeId` (opcional) pide los
    // anteriores a ese cambio, para paginar.
    List<DispatchConfigChange> history(int limit, Long beforeId);
}
