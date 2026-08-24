package com.areascript.taxiapp.security;

import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.http.HttpServletRequest;

public final class FirebaseSecurityUtils {

    private FirebaseSecurityUtils() {
    }

    public static FirebaseToken getToken(HttpServletRequest request) {
        return (FirebaseToken) request.getAttribute(FirebaseAuthenticationFilter.FIREBASE_TOKEN_ATTRIBUTE);
    }

    public static boolean isAdmin(HttpServletRequest request) {
        FirebaseToken token = getToken(request);
        return token != null && Boolean.TRUE.equals(token.getClaims().get("admin"));
    }
}
