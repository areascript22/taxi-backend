package com.areascript.taxiapp.user.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.user.domain.UserAccount;
import com.areascript.taxiapp.user.domain.UserAccounts;
import com.areascript.taxiapp.user.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Las personas y su rol en Postgres (tabla users).
//
// Condicional a taxiapp.persistence.postgres (por defecto activo): los tests de
// caracterización corren sin base de datos y ponen su propia implementación en
// memoria.
@Component
@ConditionalOnProperty(name = "taxiapp.persistence.postgres", havingValue = "true", matchIfMissing = true)
public class PostgresUserAccounts implements UserAccounts {

    private static final Logger log = LoggerFactory.getLogger(PostgresUserAccounts.class);

    // Coinciden con los CHECK de longitud de la migración V3: un texto más largo
    // haría fallar el INSERT y, con él, el request completo.
    private static final int MAX_EMAIL = 254;
    private static final int MAX_NAME = 100;

    private final UserRowRepository users;

    public PostgresUserAccounts(UserRowRepository users) {
        this.users = users;
    }

    @Override
    @Transactional
    public UserAccount findOrProvision(String firebaseUid, String email, String displayName) {
        try {
            UserRow row = users.findByFirebaseUid(firebaseUid).orElse(null);
            if (row == null) {
                users.insertIfAbsent(
                        UUID.randomUUID(),
                        firebaseUid,
                        truncate(email, MAX_EMAIL),
                        truncate(displayName, MAX_NAME),
                        "");
                row = users.findByFirebaseUid(firebaseUid).orElseThrow();
                log.info("UserDebug | Persona nueva registrada: uid={}", firebaseUid);
            }
            return toAccount(row);
        } catch (DataAccessException e) {
            log.error("UserDebug | No se pudo consultar a uid={}: {}", firebaseUid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo consultar al usuario", e);
        }
    }

    @Override
    @Transactional
    public void setRole(String firebaseUid, UserRole role) {
        try {
            users.insertIfAbsent(UUID.randomUUID(), firebaseUid, "", "", "");
            users.updateRole(firebaseUid, role.code());
        } catch (DataAccessException e) {
            log.error("UserDebug | No se pudo cambiar el rol de uid={}: {}", firebaseUid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo guardar el rol del usuario", e);
        }
    }

    @Override
    @Transactional
    public void remove(String firebaseUid) {
        try {
            users.deleteByFirebaseUid(firebaseUid);
        } catch (DataAccessException e) {
            log.error("UserDebug | No se pudo borrar a uid={}: {}", firebaseUid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo borrar al usuario", e);
        }
    }

    private static UserAccount toAccount(UserRow row) {
        String displayName = (row.getFirstName() + " " + row.getLastName()).trim();
        return new UserAccount(
                row.getId(),
                row.getFirebaseUid(),
                row.getEmail(),
                displayName,
                UserRole.fromStored(row.getRoleCode()),
                row.isBlocked());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
