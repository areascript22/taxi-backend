package com.areascript.taxiapp.driveradmin.domain;

import java.util.Optional;

// Puerto de salida: dónde viven los conductores y sus vehículos. Hoy es
// Firestore; la migración a Postgres cambia solo la implementación.
//
// Cualquier fallo del almacenamiento se traduce a ExternalServiceException
// (common.error). Los métodos que modifican NO verifican que el conductor
// exista: el caso de uso ya lo hizo con findById.
public interface DriverRepository {

    // Vacío si no existe.
    Optional<Driver> findById(String uid);

    // Todos los conductores, sin paginar (lo usa el buscador del panel).
    DriverPage findAll();

    // Una página ordenada por uid. cursor es el último uid de la página
    // anterior (null o vacío para la primera); pageSize ya viene acotado.
    DriverPage findPage(int pageSize, String cursor);

    // Cambia el campo role. Falla si el conductor no existe.
    void updateRole(String uid, DriverRole role);

    // Como updateRole pero crea el documento si hace falta, sin tocar el resto
    // de sus campos. Lo usa el arranque para asegurar el rol del fundador.
    void ensureRole(String uid, DriverRole role);

    // Registra la decisión de un admin y, según el estado, quién y cuándo:
    // aprobar limpia el motivo de rechazo; rechazar guarda el motivo.
    void applyApproval(String uid, ApprovalStatus status, String reason, String actorUid);

    // Bloquea (con motivo, quién y cuándo) o desbloquea (limpia esos datos).
    void applyBlock(String uid, boolean blocked, String reason, String actorUid);
}
