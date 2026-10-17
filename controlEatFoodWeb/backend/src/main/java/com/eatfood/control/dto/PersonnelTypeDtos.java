package com.eatfood.control.dto;
import jakarta.validation.constraints.*;
public final class PersonnelTypeDtos {
    private PersonnelTypeDtos() {}
    public record Request(@NotBlank @Size(max=80) String name, @Size(max=30) String code, Integer sortOrder) {}
    public record UpdateRequest(@NotBlank @Size(max=80) String name, Integer sortOrder, Boolean active) {}
    public record Response(Long id, String code, String name, int sortOrder, boolean active) {}
}
