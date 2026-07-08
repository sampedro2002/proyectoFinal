package com.eatfood.control.security;

import com.eatfood.control.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService;
    private final DeviceRepository deviceRepository;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        URI uri = request.getURI();
        Map<String, List<String>> queryParams = UriComponentsBuilder.fromUri(uri).build().getQueryParams();

        String token = first(queryParams.get("token"));
        String deviceToken = first(queryParams.get("deviceToken"));

        // ── Ruta 1: JWT (panel de administración / registro de huellas) ──────
        if (token != null && !token.isBlank() && jwtService.isValid(token)) {
            try {
                var claims = jwtService.parse(token);
                attributes.put("username", claims.getSubject());
                attributes.put("restaurantId", claims.get("restaurantId"));
                if (deviceToken != null && !deviceToken.isBlank()) {
                    attributes.put("deviceToken", deviceToken);
                    log.info("[WS AUTH] JWT válido — usuario='{}', dispositivo='{}'",
                            claims.getSubject(), deviceToken);
                } else {
                    log.info("[WS AUTH] JWT válido — usuario='{}'", claims.getSubject());
                }
                return true;
            } catch (Exception e) {
                log.error("[WS AUTH] Error procesando claims del token: {}", e.getMessage());
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
        }

        // ── Ruta 2: token de dispositivo (Kiosk — sin JWT de usuario) ────────
        if (deviceToken != null && !deviceToken.isBlank()) {
            boolean validDevice = deviceRepository.findBySessionToken(deviceToken)
                    .map(d -> d.isConnected())
                    .orElse(false);
            if (validDevice) {
                attributes.put("deviceToken", deviceToken);
                log.info("[WS AUTH] Kiosk autenticado — deviceToken='{}'", deviceToken);
                return true;
            }
            log.warn("[WS AUTH] Conexión rechazada: deviceToken='{}' no corresponde a un dispositivo conectado.",
                    deviceToken);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        log.warn("[WS AUTH] Conexión rechazada: sin credenciales válidas en el handshake.");
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No requiere lógica adicional post-handshake
    }

    /** Devuelve el primer valor de una lista de parámetros (o null si no existe). */
    private static String first(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        String v = values.get(0);
        return v == null ? null : v;
    }
}
