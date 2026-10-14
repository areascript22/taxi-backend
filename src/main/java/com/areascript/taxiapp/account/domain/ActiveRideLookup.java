package com.areascript.taxiapp.account.domain;

// Puerto de salida: ¿este usuario tiene un viaje en curso, como pasajero o
// como conductor? Lo implementa la feature de viajes; borrar la cuenta en
// medio de un viaje dejaría a la otra parte sin contraparte.
public interface ActiveRideLookup {

    boolean hasActiveRide(String uid);
}
