package com.areascript.taxiapp.user.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.dispatch.domain.DriverPresence;
import com.areascript.taxiapp.dispatch.domain.NearbyDriver;
import com.areascript.taxiapp.dispatch.domain.PresenceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

// La presencia de los conductores en Postgres/PostGIS: las columnas is_online y
// on_ride de drivers y la tabla driver_locations.
//
// Usa SQL directo (JdbcTemplate) y no JPA: el tipo `geography` y las funciones
// de PostGIS (ST_DWithin, ST_Distance) se expresan mejor y sin dependencias
// extra en SQL, y son operaciones de una sola sentencia sobre filas que no hace
// falta cargar como entidades.
//
// El punto se construye SIEMPRE como ST_MakePoint(longitud, latitud): en ESE
// orden, al revés de como se suele decir "lat, lng".
@Component
@ConditionalOnProperty(name = "taxiapp.persistence.postgres", havingValue = "true", matchIfMissing = true)
public class PostgresDriverPresence implements DriverPresence {

    private static final Logger log = LoggerFactory.getLogger(PostgresDriverPresence.class);

    // `driver` = la fila de drivers cuyo usuario tiene ese uid de Firebase.
    private static final String DRIVER_ID_OF_UID =
            "(SELECT d.user_id FROM drivers d JOIN users u ON u.id = d.user_id WHERE u.firebase_uid = ?)";

    private final JdbcTemplate jdbc;

    public PostgresDriverPresence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PresenceState> state(String driverUid) {
        try {
            List<PresenceState> rows = jdbc.query(
                    "SELECT d.is_online, d.on_ride FROM drivers d JOIN users u ON u.id = d.user_id "
                            + "WHERE u.firebase_uid = ?",
                    (rs, i) -> new PresenceState(rs.getBoolean(1), rs.getBoolean(2)),
                    driverUid);
            return rows.stream().findFirst();
        } catch (DataAccessException e) {
            throw failure("leer la presencia", driverUid, e);
        }
    }

    @Override
    @Transactional
    public boolean setOnline(String driverUid, boolean online) {
        try {
            int updated = jdbc.update(
                    "UPDATE drivers SET is_online = ?, updated_at = now() WHERE user_id = " + DRIVER_ID_OF_UID,
                    online, driverUid);
            if (!online) {
                // Quien deja de trabajar no deja rastro de dónde estaba.
                jdbc.update("DELETE FROM driver_locations WHERE driver_id = " + DRIVER_ID_OF_UID, driverUid);
            }
            return updated > 0;
        } catch (DataAccessException e) {
            throw failure("cambiar el estado en línea", driverUid, e);
        }
    }

    @Override
    @Transactional
    public void setOnRide(String driverUid, boolean onRide) {
        try {
            jdbc.update(
                    "UPDATE drivers SET on_ride = ? WHERE user_id = " + DRIVER_ID_OF_UID,
                    onRide, driverUid);
        } catch (DataAccessException e) {
            throw failure("cambiar el estado de carrera", driverUid, e);
        }
    }

    @Override
    @Transactional
    public boolean recordLocationIfAvailable(String driverUid, double latitude, double longitude) {
        try {
            // Una sola sentencia: inserta o actualiza SOLO si el conductor existe,
            // está en línea y no está en carrera. Si no cumple, no toca nada y
            // devuelve 0 filas.
            int written = jdbc.update(
                    "INSERT INTO driver_locations (driver_id, location, updated_at) "
                            + "SELECT d.user_id, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, now() "
                            + "FROM drivers d JOIN users u ON u.id = d.user_id "
                            + "WHERE u.firebase_uid = ? AND d.is_online AND NOT d.on_ride "
                            + "ON CONFLICT (driver_id) DO UPDATE "
                            + "SET location = EXCLUDED.location, updated_at = EXCLUDED.updated_at",
                    longitude, latitude, driverUid);
            return written > 0;
        } catch (DataAccessException e) {
            throw failure("guardar la ubicación", driverUid, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<NearbyDriver> findAvailableNear(
            double latitude,
            double longitude,
            int radiusMeters,
            Duration maxAge,
            int limit,
            Collection<String> excludeUids) {
        // No incluye el filtro de aprobación ni de bloqueo: esas dos cosas
        // todavía no se reflejan en Postgres (siguen en Firestore). Se exigen al
        // ponerse en línea y de nuevo al aceptar la carrera. Cuando se
        // sincronicen, se agregan acá.
        String sql = "SELECT u.firebase_uid, ST_Distance(l.location, p.point) AS meters "
                + "FROM driver_locations l "
                + "JOIN drivers d ON d.user_id = l.driver_id "
                + "JOIN users u ON u.id = d.user_id "
                + "CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS point) p "
                + "WHERE d.is_online AND NOT d.on_ride "
                + "AND l.updated_at > now() - make_interval(secs => ?) "
                + "AND ST_DWithin(l.location, p.point, ?) "
                + "AND NOT (u.firebase_uid = ANY (?)) "
                + "ORDER BY meters, u.firebase_uid "
                + "LIMIT ?";
        try {
            return jdbc.query(
                    connection -> {
                        var statement = connection.prepareStatement(sql);
                        Array excluded = connection.createArrayOf("text", excludeUids.toArray());
                        statement.setDouble(1, longitude);
                        statement.setDouble(2, latitude);
                        statement.setDouble(3, maxAge.toMillis() / 1000.0);
                        statement.setInt(4, radiusMeters);
                        statement.setArray(5, excluded);
                        statement.setInt(6, limit);
                        return statement;
                    },
                    (rs, i) -> new NearbyDriver(rs.getString(1), rs.getDouble(2)));
        } catch (DataAccessException e) {
            throw failure("buscar conductores cercanos", "punto", e);
        }
    }

    private static ExternalServiceException failure(String action, String subject, DataAccessException e) {
        log.error("DispatchDebug | No se pudo {} de {}: {}", action, subject, e.getMessage(), e);
        return new ExternalServiceException("No se pudo " + action, e);
    }
}
