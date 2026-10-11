package com.areascript.taxiapp.user.infra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Acceso a la tabla `users`. Solo lo usa infra.
public interface UserRowRepository extends JpaRepository<UserRow, UUID> {

    // La búsqueda de cada request autenticado: del uid del token a la fila.
    Optional<UserRow> findByFirebaseUid(String firebaseUid);

    List<UserRow> findAllByFirebaseUidIn(Collection<String> firebaseUids);
}
