package com.eatfood.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public final class FingerprintDtos {

    public record EnrollRequest(
            @NotNull Long employeeId,
            @NotNull Short fingerIndex,
            @NotBlank String templateB64) {}

    public record FingerprintResponse(
            Long id,
            Long employeeId,
            short fingerIndex,
            Long enrolledBy,
            OffsetDateTime enrolledAt,
            boolean active) {}
}
