package com.eatfood.control.web;

import com.eatfood.control.config.AppProperties;
import com.eatfood.control.dto.AuthDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Autenticación")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /**
     * Cookie httpOnly que transporta el refreshToken para el cliente web (SPA), acotada
     * con Path=/api/auth (el navegador solo la adjunta en login/refresh/logout). El cuerpo
     * JSON de la respuesta sigue incluyendo refreshToken como siempre: la app móvil (que no
     * usa cookies) sigue leyéndolo de ahí sin cambios. Este endpoint acepta el refreshToken
     * tanto en el body (móvil) como en esta cookie (web), lo que llegue primero.
     */
    private static final String REFRESH_COOKIE = "refreshToken";

    private final AuthService authService;
    private final AppProperties props;

    @Operation(summary = "Inicia sesión y devuelve tokens JWT")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req,
                               HttpServletRequest httpReq, HttpServletResponse httpRes) {
        AuthResponse auth = authService.login(req);
        setRefreshCookie(httpReq, httpRes, auth.refreshToken());
        return auth;
    }

    @Operation(summary = "Renueva el access token usando el refresh token (body JSON para móvil, cookie httpOnly para el navegador)")
    @PostMapping("/refresh")
    public AuthResponse refresh(@RequestBody(required = false) RefreshRequest body,
                                 @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
                                 HttpServletRequest httpReq, HttpServletResponse httpRes) {
        AuthResponse auth = authService.refresh(new RefreshRequest(resolveToken(body, cookieToken)));
        setRefreshCookie(httpReq, httpRes, auth.refreshToken());
        return auth;
    }

    @Operation(summary = "Cierra la sesión (revoca el refresh token)")
    @PostMapping("/logout")
    public void logout(@RequestBody(required = false) RefreshRequest body,
                        @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
                        HttpServletRequest httpReq, HttpServletResponse httpRes) {
        String token = cookieToken != null && !cookieToken.isBlank() ? cookieToken
                : (body != null ? body.refreshToken() : null);
        if (token != null && !token.isBlank()) {
            authService.logout(token);
        }
        clearRefreshCookie(httpReq, httpRes);
    }

    private static String resolveToken(RefreshRequest body, String cookieToken) {
        if (cookieToken != null && !cookieToken.isBlank()) return cookieToken;
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) return body.refreshToken();
        throw new BusinessException("INVALID_REFRESH", "Refresh token faltante.");
    }

    private void setRefreshCookie(HttpServletRequest req, HttpServletResponse res, String value) {
        long maxAgeSeconds = props.getSecurity().getJwt().getRefreshTokenDays() * 24L * 3600L;
        res.addHeader(HttpHeaders.SET_COOKIE, buildCookie(req, value, maxAgeSeconds).toString());
    }

    private void clearRefreshCookie(HttpServletRequest req, HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, buildCookie(req, "", 0).toString());
    }

    /**
     * Secure se decide por request (request.isSecure(), que respeta X-Forwarded-Proto gracias a
     * server.forward-headers-strategy=framework): fijarlo siempre en true rompería el login en el
     * despliegue LAN por HTTP plano de RunWindowns/Inicio.ps1; fijarlo siempre en false degradaría
     * un despliegue con TLS/reverse proxy (ver app.public-url).
     */
    private ResponseCookie buildCookie(HttpServletRequest req, String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(req.isSecure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
