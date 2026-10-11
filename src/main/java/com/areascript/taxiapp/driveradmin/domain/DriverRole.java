package com.areascript.taxiapp.driveradmin.domain;

import java.util.Optional;

// Rol de un conductor dentro del sistema. Es el valor que se guarda en
// drivers/{uid}.role; los privilegios reales (claims de Firebase) los asigna
// DriverAdminService en el mismo paso.
public enum DriverRole {
    DRIVER("driver"),
    ADMIN("admin"),
    SUPERUSER("superuser");

    private final String wireValue;

    DriverRole(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    // Vacío si no es un rol conocido.
    public static Optional<DriverRole> parse(String value) {
        for (DriverRole role : values()) {
            if (role.wireValue.equals(value)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    // Para lo que ya está guardado: sin el campo, o con un valor que no es un
    // rol, es un conductor corriente. Un valor corrupto nunca otorga privilegios.
    public static DriverRole fromStored(Object stored) {
        return stored == null ? DRIVER : parse(stored.toString()).orElse(DRIVER);
    }
}
