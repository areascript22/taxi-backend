package com.areascript.taxiapp.common.security;

import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.user.domain.UserAccount;
import com.areascript.taxiapp.user.domain.UserAccounts;
import com.areascript.taxiapp.user.domain.UserRole;
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
// QUÉ PUEDE HACER la persona lo decide la base de datos (users.role_code), no
// los claims del token: el token solo prueba QUIÉN es. Así un cambio de rol
// vale en el siguiente request y no cuando el token se renueve (hasta 1 hora).
//
// No es un @Component a propósito: Boot registraría cualquier Filter-bean
// también como filtro del servlet, y correría dos veces. Lo instancia
// SecurityConfig y lo coloca dentro de la cadena de seguridad.
public class FirebaseTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final FirebaseAuth firebaseAuth;
    private final TaxiappProperties.Admin adminSettings;
    private final UserAccounts userAccounts;

    public FirebaseTokenAuthenticationFilter(
            FirebaseAuth firebaseAuth,
            TaxiappProperties.Admin adminSettings,
            UserAccounts userAccounts
    ) {
        this.firebaseAuth = firebaseAuth;
        this.adminSettings = adminSettings;
        this.userAccounts = userAccounts;
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
                UserAccount account = userAccounts.findOrProvision(
                        decoded.getUid(), decoded.getEmail(), decoded.getName());

                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(toAuthentication(decoded, account));
                SecurityContextHolder.setContext(context);
            } catch (FirebaseAuthException e) {
                log.warn("AuthDebug | Token de Firebase inválido o expirado: {}", e.getMessage());
                // Sin autenticar: Spring Security responderá 401.
            } catch (ExternalServiceException e) {
                // El token es válido pero no se puede saber qué puede hacer la
                // persona. Sin la base no se autoriza a nadie (falla cerrado), y
                // se dice que es un problema del servicio, no de las credenciales.
                log.error("AuthDebug | No se pudo consultar la base de usuarios: {}", e.getMessage(), e);
                SecurityConfig.writeProblem(response, HttpStatus.SERVICE_UNAVAILABLE,
                        "El servicio no está disponible temporalmente. Intenta de nuevo en un momento");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private UsernamePasswordAuthenticationToken toAuthentication(FirebaseToken token, UserAccount account) {
        UserRole role = account.role();
        boolean founder = adminSettings.isFounder(token.getUid(), token.getEmail());

        warnIfClaimsDrifted(token, account);

        List<GrantedAuthority> authorities = new ArrayList<>();
        if (role.isAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        if (role.isSuperUser()) {
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
                role.isAdmin(),
                role.isSuperUser()
        );
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }

    // Mientras los claims sigan existiendo (las reglas de Firestore y Storage los
    // leen), la base y el token tienen que decir lo mismo. Si no, o la base se
    // quedó atrás o un claim no se sincronizó: se deja constancia para
    // corregirlo; la decisión ya la tomó la base.
    private void warnIfClaimsDrifted(FirebaseToken token, UserAccount account) {
        Map<String, Object> claims = token.getClaims() == null ? Map.of() : token.getClaims();
        boolean claimAdmin = Boolean.TRUE.equals(claims.get("admin"));
        boolean claimSuperUser = Boolean.TRUE.equals(claims.get("superuser"));
        UserRole role = account.role();
        if (claimAdmin != role.isAdmin() || claimSuperUser != role.isSuperUser()) {
            log.warn("AuthDebug | Rol desalineado para uid={}: token(admin={}, superuser={}) vs base(role={}). Manda la base",
                    token.getUid(), claimAdmin, claimSuperUser, role.code());
        }
    }
}
