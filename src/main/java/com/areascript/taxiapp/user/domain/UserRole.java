package com.areascript.taxiapp.user.domain;

import java.util.Optional;

// Nivel de privilegio de una persona. Es el valor de users.role_code (clave
// foránea a la tabla roles).
//
// "Ser conductor" NO es un rol: es tener perfil de conductor. Un admin puede ser
// además conductor o pasajero.
public enum UserRole {
    USER("user"),
    ADMIN("admin"),
    SUPERUSER("superuser");

    private final String code;

    UserRole(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    // ¿Puede usar las funciones de administración? Un superuser también.
    public boolean isAdmin() {
        return this != USER;
    }

    public boolean isSuperUser() {
        return this == SUPERUSER;
    }

    public static Optional<UserRole> parse(String code) {
        for (UserRole role : values()) {
            if (role.code.equals(code)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    // Para lo que ya está guardado: un valor desconocido es un usuario
    // corriente. Un dato corrupto nunca otorga privilegios.
    public static UserRole fromStored(String code) {
        return parse(code).orElse(USER);
    }
}
