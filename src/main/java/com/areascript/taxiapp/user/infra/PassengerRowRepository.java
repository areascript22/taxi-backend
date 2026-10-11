package com.areascript.taxiapp.user.infra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// Acceso a la tabla `passengers`. Solo lo usa infra.
public interface PassengerRowRepository extends JpaRepository<PassengerRow, UUID> {
}
