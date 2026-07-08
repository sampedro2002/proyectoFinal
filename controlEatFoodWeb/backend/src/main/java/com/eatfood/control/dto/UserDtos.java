package com.eatfood.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class UserDtos {

    public record UserRequest(
            @NotBlank @Size(max = 60) String username,
            @NotBlank @Size(max = 120) String fullName,
            @Size(max = 120) String email,
            // Requerida al crear; opcional (vacía = no cambiar) al actualizar.
            @Size(max = 100) String password,
            Boolean enabled,
            // Nombres de rol: ADMIN / CATERING.
            List<String> roles,
            Long restaurantId) {}

    public record UserResponse(
            Long id,
            String username,
            String fullName,
            String email,
            boolean enabled,
            List<String> roles,
            Long restaurantId,
            String restaurantName) {}

    public record PasswordResetRequest(
            @NotBlank @Size(min = 6, max = 100) String password) {}

    public record EnabledRequest(boolean enabled) {}

    public record RoleResponse(Long id, String name, String description) {}
}
