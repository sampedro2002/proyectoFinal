package com.eatfood.control.security;

import com.eatfood.control.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador de tasa en memoria (ventana fija de 1 minuto, por IP + ruta) para los
 * endpoints más sensibles al abuso:
 * <ul>
 *   <li><b>Autenticación</b>: {@code POST /api/auth/login}, {@code POST /api/auth/refresh}
 *       — limita fuerza bruta e inundación de intentos de credenciales.</li>
 *   <li><b>Dispositivos de catering</b>: {@code POST /api/scan}, {@code POST /api/scan/connect}
 *       — limita el registro masivo de escaneos y de conexiones.</li>
 * </ul>
 *
 * <p>Al exceder el máximo dentro de la ventana devuelve {@code 429 Too Many Requests}
 * con cuerpo JSON {@code {"code":"RATE_LIMITED", ...}}. NO se aplica al polling del
 * kiosko ({@code GET /api/scan/today}) para no interferir con su refresco.</p>
 *
 * <p>Es una defensa best-effort <b>por instancia</b> (no distribuida) que complementa
 * el bloqueo por credenciales del {@code AuthService}. En despliegues con varias
 * réplicas, añada además rate-limiting en el proxy inverso.</p>
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;
    /** Cota de seguridad para el mapa de contadores (evita crecimiento no acotado). */
    private static final int MAX_ENTRIES = 20_000;

    private final AppProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public RateLimitFilter(AppProperties props) {
        this.props = props;
    }

    /** Contador mutable de una ventana; el acceso se sincroniza en incrementAndCheck. */
    private static final class Counter {
        long windowStart;
        int count;
        Counter(long windowStart) { this.windowStart = windowStart; this.count = 1; }
    }

    /** Límite aplicable a la petición, o -1 si la ruta no está limitada. */
    private int limitFor(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return -1;
        String path = request.getServletPath();
        if ("/api/auth/login".equals(path) || "/api/auth/refresh".equals(path)) {
            return props.getRateLimit().getAuthPerMinute();
        }
        if ("/api/scan".equals(path) || "/api/scan/connect".equals(path)) {
            return props.getRateLimit().getScanPerMinute();
        }
        return -1;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        int limit = props.getRateLimit().isEnabled() ? limitFor(request) : -1;
        if (limit <= 0) {
            chain.doFilter(request, response);
            return;
        }

        String key = clientIp(request) + "|" + request.getServletPath();
        if (isOverLimit(key, limit)) {
            log.warn("[RATE-LIMIT] Bloqueado: key={}, límite={}/min", key, limit);
            response.setStatus(429); // 429 Too Many Requests
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "code", "RATE_LIMITED",
                    "message", "Demasiadas peticiones. Espere un momento e inténtelo de nuevo."));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isOverLimit(String key, int limit) {
        long now = System.currentTimeMillis();
        if (counters.size() > MAX_ENTRIES) purgeExpired(now);
        Counter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart >= WINDOW_MS) {
                return new Counter(now);
            }
            existing.count++;
            return existing;
        });
        return counter.count > limit;
    }

    /** Elimina ventanas ya expiradas para acotar el uso de memoria. */
    private void purgeExpired(long now) {
        counters.entrySet().removeIf(e -> now - e.getValue().windowStart >= WINDOW_MS);
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
