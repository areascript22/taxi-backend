package com.areascript.taxiapp.identity.domain;

import java.util.Optional;

// Puerto de salida: la cuenta de autenticación de un usuario (hoy Firebase
// Authentication). Aunque los datos de los usuarios migren a otra base, el
// login sigue siendo del proveedor de identidad: esta interfaz no cambia.
//
// Las implementaciones traducen sus errores al vocabulario de common.error:
//   - cuenta inexistente  -> NotFoundException (salvo findRoles, que devuelve vacío)
//   - cualquier otro fallo -> ExternalServiceException
public interface IdentityProvider {

    // Vacío si la cuenta no existe.
    Optional<AccountRoles> findRoles(String uid);

    // Reemplaza los privilegios de la cuenta. NotFoundException si no existe.
    void setRoles(String uid, boolean admin, boolean superUser);

    // Borra la cuenta. NotFoundException si ya no existe.
    void delete(String uid);
}
