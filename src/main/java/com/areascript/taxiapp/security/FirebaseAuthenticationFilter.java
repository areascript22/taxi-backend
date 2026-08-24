package com.areascript.taxiapp.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    public static final String FIREBASE_TOKEN_ATTRIBUTE = "firebaseToken";

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final FirebaseAuth firebaseAuth;

    public FirebaseAuthenticationFilter(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Falta el header Authorization: Bearer <idToken>");
            return;
        }

        String idToken = header.substring(BEARER_PREFIX.length());
        try {
            FirebaseToken decodedToken = firebaseAuth.verifyIdToken(idToken);
            request.setAttribute(FIREBASE_TOKEN_ATTRIBUTE, decodedToken);
            filterChain.doFilter(request, response);
        } catch (FirebaseAuthException e) {
            log.warn("AuthDebug | Token de Firebase inválido o expirado: {}", e.getMessage());
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Token inválido o expirado");
        }
    }
}
