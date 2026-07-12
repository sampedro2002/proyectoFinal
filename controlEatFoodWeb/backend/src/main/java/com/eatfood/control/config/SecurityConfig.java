package com.eatfood.control.config;

import com.eatfood.control.security.JwtAuthFilter;
import com.eatfood.control.security.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletResponse;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AppProperties props;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CSRF deshabilitado de forma segura: la API es stateless (SessionCreationPolicy.STATELESS).
            // El access token viaja en el header Authorization: Bearer <JWT>, que el navegador NO adjunta
            // automáticamente en peticiones cross-site, por lo que no existe el vector clásico de CSRF
            // (que depende del envío automático de cookies) para el resto de la API.
            // El refresh token del cliente web SÍ viaja en una cookie (ver AuthController), pero está
            // acotada a Path=/api/auth y marcada SameSite=Strict: el navegador no la adjunta en peticiones
            // originadas desde otro sitio, que es precisamente lo que CSRF explota. Con eso el riesgo queda
            // cubierto sin necesitar el token de CSRF de Spring Security.
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // ── Cabeceras de seguridad (defensa en profundidad) ──────────────────
            // Endurecen las respuestas del backend (API, páginas de error, Swagger UI):
            //  · CSP: restringe orígenes de recursos y bloquea plugins/base-tag; con
            //    'unsafe-inline' sólo en estilos/scripts para no romper Swagger UI.
            //  · frame-ancestors/DENY: anti-clickjacking. · Referrer-Policy: no filtra URLs.
            //  · HSTS: fuerza HTTPS (sólo se emite sobre conexiones seguras).
            //  · Permissions-Policy: desactiva APIs del navegador no usadas.
            // Nota: el SPA React se hospeda aparte; su CSP debe fijarla su servidor (nginx).
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self' 'unsafe-inline'; " +
                    "style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
                    "connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'"))
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .addHeaderWriter(new StaticHeadersWriter(
                    "Permissions-Policy", "geolocation=(), camera=(), microphone=()"))
            )
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/api/auth/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/actuator/health",
                        "/zkfinger-ws",
                        "/zkfinger-ws/**"
                ).permitAll()
                // El dispositivo de restaurant se autentica con token de dispositivo en el propio servicio
                .requestMatchers("/api/scan/**").permitAll()
                // Endpoints de API distintos de los anteriores requieren autenticacion
                .requestMatchers("/api/**").authenticated()
                // Recursos publicos de la SPA (servida desde BOOT-INF/classes/static):
                // la welcome page (index.html), assets del bundle, manifest, service worker
                // de la PWA e iconos. Sin estos matchers, anyRequest().authenticated()
                // bloquearia la carga de la SPA y el navegador veria 401 al abrir la app.
                .requestMatchers(
                        "/",
                        "/index.html",
                        "/favicon.svg",
                        "/logo.png",
                        "/manifest.webmanifest",
                        "/registerSW.js",
                        "/sw.js",
                        "/workbox-*.js",
                        "/pwa-*.png",
                        "/assets/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            // Rate-limiting antes de autenticar: protege /api/auth y /api/scan del abuso.
            // Se instancia manualmente (no es @Component) para que Spring Boot no lo
            // auto-registre como filtro global del contenedor y se ejecute dos veces.
            .addFilterBefore(new RateLimitFilter(props), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(exc -> exc
                .authenticationEntryPoint((req, res, ex) ->
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
            );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(props.getCors().getAllowedOrigins().split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Lista explícita en vez de "*": con allowCredentials(true) un comodín de headers
        // es una combinación frágil de mantener; el frontend solo necesita estos dos.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}
