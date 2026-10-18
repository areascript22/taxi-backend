package com.areascript.taxiapp.identity.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.common.error.NotFoundException;
import com.areascript.taxiapp.identity.domain.IdentityProvider;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FirebaseIdentityProvider implements IdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(FirebaseIdentityProvider.class);

    private final FirebaseAuth firebaseAuth;

    public FirebaseIdentityProvider(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
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
