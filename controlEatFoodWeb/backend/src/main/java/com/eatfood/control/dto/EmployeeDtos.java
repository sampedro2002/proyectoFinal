package com.eatfood.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class EmployeeDtos {

    public record EmployeeRequest(
            @NotBlank @Size(max = 20) String identityCard,
            @NotBlank @Size(max = 160) String fullName,
            @Size(max = 500) String observation,
            Boolean isPassport,
            String status,
            Boolean allowsLunch,
            Boolean allowsSnack) {}

    public record EmployeeResponse(
            Long id,
            String identityCard,
            String fullName,
            String publicCode,
            String observation,
            String status,
            boolean allowsLunch,
            boolean allowsSnack,
            // Alias de allowsSnack para compatibilidad con la APK móvil (consume effectiveSnack).
            boolean effectiveSnack,
            int fingerprintCount) {}
}
