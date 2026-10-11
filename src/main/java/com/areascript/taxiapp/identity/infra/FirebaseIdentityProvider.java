package com.areascript.taxiapp.identity.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.identity.domain.AccountRoles;
import com.areascript.taxiapp.identity.domain.IdentityProvider;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class FirebaseIdentityProvider implements IdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(FirebaseIdentityProvider.class);

    private final FirebaseAuth firebaseAuth;

    public FirebaseIdentityProvider(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    public Optional<AccountRoles> findRoles(String uid) {
        try {
            UserRecord user = firebaseAuth.getUser(uid);
            Map<String, Object> claims = user.getCustomClaims();
            boolean admin = claims != null && Boolean.TRUE.equals(claims.get("admin"));
            boolean superUser = claims != null && Boolean.TRUE.equals(claims.get("superuser"));
            return Optional.of(new AccountRoles(admin, superUser));
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                return Optional.empty();
            }
            log.error("IdentityDebug | Error al consultar la cuenta uid={}: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo consultar la cuenta en Firebase Authentication", e);
        }
    }

    @Override
    public void setRoles(String uid, boolean admin, boolean superUser) {
        try {
            firebaseAuth.setCustomUserClaims(uid, Map.of("admin", admin, "superuser", superUser));
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                throw accountNotFound(uid);
            }
            log.error("IdentityDebug | Error al asignar admin={} superuser={} a uid={}: {}", admin, superUser, uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo actualizar el custom claim en Firebase Authentication", e);
        }
    }

    @Override
    public void delete(String uid) {
        try {
            firebaseAuth.deleteUser(uid);
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                throw accountNotFound(uid);
            }
            log.error("IdentityDebug | Error al eliminar uid={} de Firebase Authentication: {}", uid, e.getMessage(), e);
            throw new ExternalServiceException("No se pudo eliminar el usuario de Firebase Authentication", e);
        }
    }

    private static NotFoundException accountNotFound(String uid) {
        return new NotFoundException("No existe ninguna cuenta con uid: " + uid);
    }
}
