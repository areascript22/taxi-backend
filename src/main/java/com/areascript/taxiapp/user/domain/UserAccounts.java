package com.areascript.taxiapp.user.domain;

// Puerto de salida: dónde viven las personas y su rol. De acá sale, en cada
// request, lo que la persona PUEDE hacer: el rol guardado manda sobre los claims
// del token.
//
// Cualquier fallo de almacenamiento se traduce a ExternalServiceException
// (common.error).
public interface UserAccounts {

    // La persona con ese uid de Firebase; si todavía no existe, la crea con rol
    // USER. Lo llama el filtro de seguridad en cada request autenticado, así que
    // tiene que ser rápido (una búsqueda por índice) y seguro ante dos primeros
    // requests simultáneos de la misma persona.
    //
    // Los datos de contacto solo se usan si hay que crearla; no sobrescriben a
    // los de una persona que ya existe.
    UserAccount findOrProvision(String firebaseUid, String email, String displayName);

    // La persona con ese uid, o vacío si no existe. No la crea: es para
    // consultar a un tercero (p. ej. el rol de a quien se va a moderar).
    java.util.Optional<UserAccount> find(String firebaseUid);

    // La persona con esa identidad interna (users.id), o vacío si no existe. Es
    // para mostrar "quién hizo X" a partir de un id guardado (p. ej. quién
    // editó la configuración del despacho).
    java.util.Optional<UserAccount> findById(java.util.UUID id);

    // Cambia el rol. Si la persona no existe todavía, la crea.
    void setRole(String firebaseUid, UserRole role);

    // Borra a la persona y, en cascada, sus perfiles y vehículos. No falla si no existe.
    void remove(String firebaseUid);
}
