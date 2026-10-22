package com.areascript.taxiapp.dispatch.api;

import com.areascript.taxiapp.common.error.InvalidInputException;
import com.areascript.taxiapp.dispatch.application.DispatchConfigService.RingInput;
import com.areascript.taxiapp.dispatch.application.DispatchConfigService.UpdateCommand;

import java.util.List;

// Lo que manda el panel al guardar: reemplaza la configuración completa
// (idempotente: repetir la misma llamada deja lo mismo). Los anillos van en
// orden y su número es su posición; no se manda.
//
// `expectedVersion` es la versión que el panel vio al cargar: si ya cambió, el
// servidor contesta 409 en vez de pisar el cambio de otro administrador.
//
// Los campos son objetos (no primitivos) para poder distinguir "falta" de 0 y
// contestar 400 con un mensaje, no un valor por defecto silencioso.
public record UpdateDispatchConfigRequest(
        List<Ring> rings,
        Integer locationMaxAgeSeconds,
        Long expectedVersion
) {

    public record Ring(Integer radiusMeters, Integer waitSeconds, Integer maxDrivers) {
    }

    public UpdateCommand toCommand() {
        if (rings == null || locationMaxAgeSeconds == null || expectedVersion == null) {
            throw new InvalidInputException("Faltan datos: rings, locationMaxAgeSeconds y expectedVersion");
        }
        List<RingInput> inputs = rings.stream().map(ring -> {
            if (ring == null || ring.radiusMeters() == null || ring.waitSeconds() == null
                    || ring.maxDrivers() == null) {
                throw new InvalidInputException(
                        "Cada anillo necesita radiusMeters, waitSeconds y maxDrivers");
            }
            return new RingInput(ring.radiusMeters(), ring.waitSeconds(), ring.maxDrivers());
        }).toList();
        return new UpdateCommand(inputs, locationMaxAgeSeconds, expectedVersion);
    }
}
