package com.areascript.taxiapp.user.infra;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Acceso a la tabla `users`. Solo lo usa infra.
public interface UserRowRepository extends JpaRepository<UserRow, UUID> {

    // La búsqueda de cada request autenticado: del uid del token a la fila.
    Optional<UserRow> findByFirebaseUid(String firebaseUid);

    List<UserRow> findAllByFirebaseUidIn(Collection<String> firebaseUids);

    // Crea la persona si no existe. ON CONFLICT hace que dos primeros requests
    // simultáneos de la misma persona no se pisen: el segundo no hace nada en
    // vez de fallar por la restricción de unicidad.
    @Modifying
    @Query(value = "INSERT INTO users (id, firebase_uid, email, first_name, last_name) "
            + "VALUES (:id, :uid, :email, :firstName, :lastName) "
            + "ON CONFLICT (firebase_uid) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("uid") String firebaseUid,
            @Param("email") String email,
            @Param("firstName") String firstName,
            @Param("lastName") String lastName);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserRow u SET u.roleCode = :role, u.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE u.firebaseUid = :uid")
    int updateRole(@Param("uid") String firebaseUid, @Param("role") String roleCode);

    // Las personas que TIENEN perfil de conductor, ordenadas por uid de Firebase
    // (el orden en que las ve el panel de administración). La paginación es por
    // cursor sobre ese mismo campo: estable aunque se agreguen conductores
    // mientras se navega, a diferencia de un OFFSET.
    @Query("SELECT u FROM UserRow u WHERE u.id IN (SELECT d.userId FROM DriverRow d) "
            + "ORDER BY u.firebaseUid")
    List<UserRow> findAllDriverUsers();

    @Query("SELECT u FROM UserRow u WHERE u.id IN (SELECT d.userId FROM DriverRow d) "
            + "AND u.firebaseUid > :cursor ORDER BY u.firebaseUid")
    List<UserRow> findDriverUsersAfter(@Param("cursor") String cursor, Pageable pageable);

    // Borrado masivo: la cascada de los perfiles y vehículos la hace la base
    // (ON DELETE CASCADE), no JPA.
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM UserRow u WHERE u.firebaseUid = :uid")
    int deleteByFirebaseUid(@Param("uid") String firebaseUid);
}
