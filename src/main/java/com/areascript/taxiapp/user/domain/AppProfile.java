package com.areascript.taxiapp.user.domain;

import java.util.Optional;

// Las dos apps. Una persona puede tener perfil en las dos con la misma cuenta.
public enum AppProfile {
    DRIVER("driver"),
    PASSENGER("passenger");

    private final String code;

    AppProfile(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<AppProfile> parse(String code) {
        for (AppProfile app : values()) {
            if (app.code.equals(code)) {
                return Optional.of(app);
            }
        }
        return Optional.empty();
    }
}
