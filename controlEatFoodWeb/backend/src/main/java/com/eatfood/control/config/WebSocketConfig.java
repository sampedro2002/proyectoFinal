package com.eatfood.control.config;

import com.eatfood.control.web.ZkFingerWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Configuración de WebSockets para Spring Boot.
 * Habilita el endpoint /zkfinger-ws para conectar el frontend de forma directa.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ZkFingerWebSocketHandler zkFingerWebSocketHandler;
    private final com.eatfood.control.security.JwtWebSocketHandshakeInterceptor jwtWebSocketHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(zkFingerWebSocketHandler, "/zkfinger-ws")
                .addInterceptors(jwtWebSocketHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
