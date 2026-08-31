package com.areascript.taxiapp.service;

// Lanzada cuando quien llama a una operación sobre una carrera (cancelar,
// finalizar) no es ni el pasajero dueño ni el conductor asignado.
public class RideForbiddenException extends RuntimeException {

    public RideForbiddenException(String passengerId) {
        super("El llamador no está autorizado para esta operación sobre passengerId=" + passengerId);
    }
}
