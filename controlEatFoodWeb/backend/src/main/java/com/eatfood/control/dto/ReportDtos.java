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
            boolean offline) {}

    public record DashboardStats(
            LocalDate date,
            long totalConsumptions,
            long desayunoCount,
            long almuerzoCount,
            long expectedEmployees,
            long employeesConsumed,
            long employeesPending,
            double consumptionPercentage,
            long failedNotFound,
            long failedOutOfSchedule) {}

    public record EmployeeNotConsumed(Long employeeId, String identityCard, String fullName) {}

    public record TrendPoint(LocalDate date, long records) {}
}
