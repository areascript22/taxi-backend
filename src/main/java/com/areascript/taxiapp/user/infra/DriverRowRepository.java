package com.areascript.taxiapp.user.infra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// Acceso a la tabla `drivers`. Solo lo usa infra; el resto del servidor habla
// con el puerto DriverRepository del dominio.
public interface DriverRowRepository extends JpaRepository<DriverRow, UUID> {
}
