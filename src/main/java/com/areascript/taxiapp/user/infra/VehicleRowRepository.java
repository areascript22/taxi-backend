package com.areascript.taxiapp.user.infra;

import org.springframework.data.jpa.repository.JpaRepository;

// Acceso a la tabla `vehicles`. Solo lo usa infra.
public interface VehicleRowRepository extends JpaRepository<VehicleRow, String> {
}
