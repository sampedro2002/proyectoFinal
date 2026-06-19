package com.eatfood.control.dto;

import jakarta.validation.constraints.NotBlank;

public final class EmployeeDtos {

    public record EmployeeRequest(
            @NotBlank String identityCard,
            @NotBlank String fullName,
            Long positionId,
            String status,
            Boolean allowsLunch,
            Boolean allowsSnack) {}

    public record EmployeeResponse(
            Long id,
            String identityCard,
            String fullName,
            Long positionId,
            String positionName,
            String status,
            boolean allowsLunch,
            boolean effectiveSnack,
            int fingerprintCount) {}
}
