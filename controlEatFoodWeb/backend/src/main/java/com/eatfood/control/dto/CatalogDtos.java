package com.eatfood.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public final class CatalogDtos {

    public record CateringRequest(
            @NotBlank String name,
            String location,
            Boolean active,
            Integer maxDevices) {}

    public record CateringResponse(
            Long id, String name, String location, boolean active, int maxDevices, long connectedDevices) {}

    public record MealTypeRequest(
            @NotBlank String code, @NotBlank String name, Integer sortOrder, Boolean active) {}

    public record MealTypeResponse(Long id, String code, String name, boolean active, int sortOrder) {}

    public record ScheduleRequest(
            @NotNull Long mealTypeId,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            Boolean active) {}

    public record ScheduleResponse(
            Long id, Long mealTypeId, String mealTypeName,
            LocalTime startTime, LocalTime endTime, boolean active) {}
}
