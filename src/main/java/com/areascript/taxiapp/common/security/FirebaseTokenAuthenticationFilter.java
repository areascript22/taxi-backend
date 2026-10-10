package com.areascript.taxiapp.common.security;

import com.areascript.taxiapp.common.config.TaxiappProperties;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Autentica cada request con el ID token de Firebase ("Authorization: Bearer
// <token>"). Si el token es válido deja la identidad en el SecurityContext; si
// falta o es inválido NO responde acá: sigue la cadena sin autenticar, y es
// Spring Security quien responde 401 (ver SecurityConfig).
//
// No es un @Component a propósito: Boot registraría cualquier Filter-bean
// también como filtro del servlet, y correría dos veces. Lo instancia
// SecurityConfig y lo coloca dentro de la cadena de seguridad.
public class FirebaseTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final FirebaseAuth firebaseAuth;
    private final TaxiappProperties.Admin adminSettings;

    public FirebaseTokenAuthenticationFilter(FirebaseAuth firebaseAuth, TaxiappProperties.Admin adminSettings) {
        this.firebaseAuth = firebaseAuth;
        this.adminSettings = adminSettings;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String idToken = header.substring(BEARER_PREFIX.length());
            try {
                FirebaseToken decoded = firebaseAuth.verifyIdToken(idToken);
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(toAuthentication(decoded));
                SecurityContextHolder.setContext(context);
            } catch (FirebaseAuthException e) {
                log.warn("AuthDebug | Token de Firebase inválido o expirado: {}", e.getMessage());
                // Sin autenticar: Spring Security responderá 401.
            }
        }

        filterChain.doFilter(request, response);
    }

    private UsernamePasswordAuthenticationToken toAuthentication(FirebaseToken token) {
        Map<String, Object> claims = token.getClaims() == null ? Map.of() : token.getClaims();
        boolean admin = Boolean.TRUE.equals(claims.get("admin"));
        boolean superUser = Boolean.TRUE.equals(claims.get("superuser"));
        boolean founder = adminSettings.isFounder(token.getUid(), token.getEmail());

        List<GrantedAuthority> authorities = new ArrayList<>();
        if (admin) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        if (superUser) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SUPERUSER"));
        }
        if (founder) {
            authorities.add(new SimpleGrantedAuthority("ROLE_FOUNDER"));
        }

        AuthenticatedUser user = new AuthenticatedUser(
                token.getUid(),
                token.getEmail(),
                token.getName(),
                token.getPicture(),
                admin,
                superUser
        );
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }
}
