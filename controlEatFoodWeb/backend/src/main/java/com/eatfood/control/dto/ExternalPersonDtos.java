package com.eatfood.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ExternalPersonDtos {

    public record ExternalPersonRequest(
            @NotBlank @Size(max = 20) String identityCard,
            @NotBlank @Size(max = 160) String fullName,
            @Size(max = 500) String observation,
            Boolean isPassport) {}

    public record ExternalPersonResponse(
            Long id,
            String identityCard,
            String fullName,
            String observation) {}
}
