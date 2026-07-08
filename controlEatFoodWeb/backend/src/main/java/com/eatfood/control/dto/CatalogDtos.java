package com.eatfood.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public final class CatalogDtos {

    public record RestaurantRequest(
            @NotBlank String name,
            String location,
            String representative,
            Boolean active,
            Integer maxDevices) {}

    public record RestaurantResponse(
            Long id, String name, String location, String representative,
            boolean active, int maxDevices, long connectedDevices) {}


    public record ScheduleRequest(
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            Boolean active) {}

    public record ScheduleResponse(
            Long id, LocalTime startTime, LocalTime endTime, boolean active) {}
}
