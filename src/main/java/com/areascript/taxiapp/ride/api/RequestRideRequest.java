package com.areascript.taxiapp.ride.api;

// `sector` es el sector de Riobamba donde cae el punto de recogida, resuelto por
// passenger_app con los polígonos de assets/json/sectors.geojson. driver_app lo
// usa para la alerta hablada ("Carrera hacia <sector>") y para la lista de
// solicitudes: el taxista escucha la referencia de la zona, no la dirección
// completa, y ve la dirección exacta al aceptar.
//
// Puede llegar vacío o null (una app vieja, o un cliente que no pudo resolverlo)
// y eso NO es un error: se persiste vacío y driver_app vuelve a leer la
// dirección, que es el comportamiento anterior a los sectores. El filtro de
// cobertura es del cliente; este endpoint no rechaza por sector.
public record RequestRideRequest(
        double latitude,
        double longitude,
        String address,
        String sector
) {
}
