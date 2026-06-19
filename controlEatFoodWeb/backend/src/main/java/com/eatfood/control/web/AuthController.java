package com.eatfood.control.web;

import com.eatfood.control.dto.AuthDtos.*;
import com.eatfood.control.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Autenticación")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Inicia sesión y devuelve tokens JWT")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @Operation(summary = "Renueva el access token usando el refresh token")
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req);
    }

    @Operation(summary = "Cierra la sesión (revoca el refresh token)")
    @PostMapping("/logout")
    public void logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
    }
}
