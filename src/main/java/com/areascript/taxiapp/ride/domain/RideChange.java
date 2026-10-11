package com.areascript.taxiapp.ride.domain;

import java.util.Map;

// La decisión de una regla sobre el estado ACTUAL de una carrera. Una regla
// (ver RideTransitions) es una función pura de "carrera actual" a RideChange;
// el repositorio la aplica de forma atómica y la puede reejecutar si los datos
// cambiaron mientras tanto, por eso no debe tener efectos secundarios.
public sealed interface RideChange {

    enum Reason {
        // Quien llama no es parte de esta carrera.
        FORBIDDEN,
        // La carrera no está en un estado que admita esta operación.
        NOT_ALLOWED
    }

    // No hay nada que modificar. También es lo que se devuelve cuando la
    // carrera no existe: el repositorio confirma sin cambios y quien llamó
    // detecta la ausencia en el resultado.
    record Unchanged() implements RideChange {
    }

    // La operación no procede. Nada se escribe.
    record Rejected(Reason reason) implements RideChange {
    }

    // Escribe estos campos. La clave es la ruta dentro de la carrera
    // ("status", "driver/data"); el valor puede ser un texto, un número, un
    // mapa, null (borra el campo) o ServerTime.NOW.
    record Update(Map<String, Object> fields) implements RideChange {
    }

    // Borra la carrera entera.
    record Remove() implements RideChange {
    }

    // Reemplaza la carrera completa por este nodo (no deja ningún campo del
    // anterior). Es lo que hace una solicitud nueva: no hereda el conductor ni
    // la cancelación de una carrera previa terminada. Los valores pueden
    // contener ServerTime.NOW.
    record Replace(Map<String, Object> node) implements RideChange {
    }

    static RideChange unchanged() {
        return new Unchanged();
    }

    static RideChange rejected(Reason reason) {
        return new Rejected(reason);
    }

    static RideChange update(Map<String, Object> fields) {
        return new Update(fields);
    }

    static RideChange remove() {
        return new Remove();
    }

    static RideChange replace(Map<String, Object> node) {
        return new Replace(node);
    }
}
