package com.eatfood.control.dto;

import jakarta.validation.constraints.NotBlank;

public final class EmployeeDtos {

    public record EmployeeRequest(
            @NotBlank String identityCard,
            @NotBlank String fullName,
            String positionTitle,
            String observation,
            String status,
            Boolean allowsLunch,
            Boolean allowsSnack) {}

    public record EmployeeResponse(
            Long id,
            String identityCard,
            String fullName,
            String publicCode,
            String positionTitle,
            // Alias de positionTitle para compatibilidad con la APK móvil (consume positionName).
            String positionName,
            String observation,
            String status,
            boolean allowsLunch,
            boolean allowsSnack,
            // Alias de allowsSnack para compatibilidad con la APK móvil (consume effectiveSnack).
            boolean effectiveSnack,
            int fingerprintCount) {}
}
