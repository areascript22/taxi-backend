package com.areascript.taxiapp.ride.domain;

// Los datos del conductor que se le muestran al pasajero al asignarlo.
// Nombre, correo y foto salen del token verificado; teléfono y vehículo, del
// perfil. Teléfono y vehículo son información complementaria: pueden ser null
// (conductor que todavía no cargó su vehículo) y la asignación procede igual.
public record DriverContact(
        String id,
        String email,
        String displayName,
        String photoUrl,
        String phoneNumber,
        String vehiclePlate,
        String vehicleBrand,
        String vehicleModel,
        String vehicleColor
) {
}
