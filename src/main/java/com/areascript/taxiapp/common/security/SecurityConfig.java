package com.areascript.taxiapp.common.security;

import com.areascript.taxiapp.common.config.TaxiappProperties;
import com.areascript.taxiapp.user.domain.UserAccounts;
import com.google.firebase.auth.FirebaseAuth;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

// Seguridad del servidor:
//   - Todo /api/** exige un ID token de Firebase válido (401 si no).
//   - Fuera de /api/** no se exige nada, igual que antes de esta fase (una
//     ruta inexistente responde 404, no 401).
//   - La autorización fina se declara en cada endpoint con @PreAuthorize
//     sobre los roles ROLE_ADMIN / ROLE_SUPERUSER / ROLE_FOUNDER que arma
//     FirebaseTokenAuthenticationFilter a partir del rol guardado en la base.
//
// Sin estado: no hay sesión ni cookies, cada request trae su token. Por eso
// tampoco aplica CSRF (que protege justamente a los clientes con cookies).
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            FirebaseAuth firebaseAuth,
            TaxiappProperties properties,
            UserAccounts userAccounts
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Lo único público de la API: la consulta de versión
                        // mínima, que las apps hacen antes de iniciar sesión.
                        // Va antes de /api/** porque se evalúa en orden.
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(forbiddenHandler()))
                .addFilterBefore(
                        new FirebaseTokenAuthenticationFilter(firebaseAuth, properties.admin(), userAccounts),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // No se usa: la autenticación es solo por token de Firebase. Declararlo
    // evita que Spring Boot cree un usuario "user" con una contraseña
    // aleatoria que imprime en el log (y que nadie debería usar).
    @Bean
    public UserDetailsService noUsersDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Este servidor solo autentica con tokens de Firebase");
        };
    }

    // 401: no hay token, o es inválido o expiró.
    private static AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, exception) -> writeProblem(
                response,
                HttpStatus.UNAUTHORIZED,
                "Falta un ID token de Firebase válido en el header Authorization: Bearer <idToken>");
    }

    // 403 cuando el rol no alcanza y la decisión la toma el filtro (las de
    // @PreAuthorize las traduce GlobalExceptionHandler).
    private static AccessDeniedHandler forbiddenHandler() {
        return (request, response, exception) -> writeProblem(
                response,
                HttpStatus.FORBIDDEN,
                "No tienes permisos para realizar esta operación");
    }

    // Mismo formato que GlobalExceptionHandler (RFC 9457). Los textos son
    // constantes sin comillas: no hace falta escapar nada.
    static void writeProblem(HttpServletResponse response, HttpStatus status, String detail)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                        + "\",\"status\":" + status.value()
                        + ",\"detail\":\"" + detail + "\"}");
    }
}
