package com.eatfood.control.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class AuthDtos {

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record AuthResponse(
            String accessToken,
            String refreshToken,
            String username,
            String fullName,
            List<String> roles,
            Long restaurantId) {}
}
