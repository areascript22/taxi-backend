package com.areascript.taxiapp.user.domain;

// Todo lo que el sistema sabe de quien hace el request. `driver` y `passenger`
// son null si esa persona no tiene perfil en esa app.
public record MyProfile(
        UserAccount account,
        DriverProfile driver,
        PassengerProfile passenger
) {
}
