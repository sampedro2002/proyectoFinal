package com.eatfood.control.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ReportDtos {

    public record ConsumptionRow(
            Long id,
            LocalDate businessDate,
            OffsetDateTime consumedAt,
            String employeeName,
            String identityCard,
            String restaurantName,
            String mealName,
            String observation,
            boolean offline,
            String method,
            String proxyEmployeeName,
            boolean cancelled) {}

    /** Etiqueta legible del método de registro, para UIs y reportes. */
    public static String methodLabel(String method) {
        if (method == null) return "Huella";
        return switch (method) {
            case "MANUAL"  -> "Manual";
            case "EXTERNAL" -> "Externo";
            default        -> "Huella";
        };
    }

    public record DashboardStats(
            LocalDate date,
            long totalConsumptions,
            long almuerzoCount,
            long meriendaCount,
            long expectedEmployees,
            long employeesConsumed,
            long employeesPending,
            double consumptionPercentage,
            long failedNotFound,
            long failedOutOfSchedule) {}

    public record EmployeeNotConsumed(Long employeeId, String identityCard, String fullName) {}

    public record TrendPoint(LocalDate date, long records) {}
}
