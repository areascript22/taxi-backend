package com.areascript.taxiapp.identity.domain;

// Puerto de salida: la cuenta de autenticación de un usuario (hoy Firebase
// Authentication). Aunque los datos de los usuarios migren a otra base, el
// login sigue siendo del proveedor de identidad: esta interfaz no cambia.
//
// Las implementaciones traducen sus errores al vocabulario de common.error:
//   - cuenta inexistente  -> NotFoundException
//   - cualquier otro fallo -> ExternalServiceException
//
// Los privilegios ya no viven acá (custom claims): los decide users.role_code.
public interface IdentityProvider {

    // Borra la cuenta. NotFoundException si ya no existe.
    void delete(String uid);
}
