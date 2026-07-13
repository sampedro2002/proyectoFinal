package com.eatfood.control.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filtro que enriquece el contexto MDC de cada petición con:
 *   · reqId  — identificador corto de la petición (para correlacionar líneas).
 *   · user   — nombre del usuario autenticado (vacío si anónimo).
 *   · ip     — IP de cliente (respetando X-Forwarded-For de un proxy inverso).
 *
 * Además deja una única línea INFO por petición al terminar (método, URI,
 * status y duración en ms). Es lo más parecido a un "morgan" en Spring:
 * con esto se puede trazar cada request end-to-end en application.log.
 *
 * El orden HIGHEST_PRECEDENCE + 1 asegura que arranca antes que el filtro
 * de seguridad y después de cualquier filtro de framework (para que el
 * usuario ya esté disponible) — pero como MDC se setea en doFilterInternal
 * y se limpia en finally, basta con que sea el primero de la cadena propia.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final int REQ_ID_LEN = 8;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String reqId = UUID.randomUUID().toString().replace("-", "").substring(0, REQ_ID_LEN);
        String user = currentUsername();
        String ip = clientIp(request);

        MDC.put("reqId", reqId);
        MDC.put("user", user);
        MDC.put("ip", ip);

        long t0 = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - t0;
            // El usuario puede haberse autenticado durante la cadena
            MDC.put("user", currentUsername());
            int status = response.getStatus();
            if (log.isInfoEnabled()) {
                log.info("{} {} {} {} {}ms",
                        request.getMethod(),
                        request.getRequestURI(),
                        status,
                        user != null ? user : "-",
                        duration);
            }
            MDC.clear();
        }
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return auth.getName();
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}