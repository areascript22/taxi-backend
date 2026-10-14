package com.areascript.taxiapp.account.domain;

// Puerto de salida: borra los datos de un usuario (perfil de conductor o
// pasajero, vehículo, foto y chats). NO borra la cuenta de autenticación: eso
// lo hace cada flujo con IdentityProvider, en momentos distintos por razones
// distintas (el usuario que se borra a sí mismo lo hace al final para poder
// reintentar; el admin que borra a un conductor lo hace al principio).
//
// Compartido por ambos flujos para que ninguno deje datos huérfanos.
//
// Contrato: es best-effort y NO relanza. Cada paso que falla se registra y se
// sigue con el siguiente, para dejar la cuenta lo más limpia posible.
public interface UserDataCleaner {

    void wipe(String uid);
}
