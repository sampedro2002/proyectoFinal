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
            String positionName,
            String cateringName,
            String mealName,
            boolean offline) {}

    public record DashboardStats(
            LocalDate date,
            long totalConsumptions,
            long lunchCount,
            long snackCount,
            long expectedEmployees,
            long employeesConsumed,
            long employeesPending,
            double consumptionPercentage,
            long failedNotFound,
            long failedOutOfSchedule) {}

    public record EmployeeNotConsumed(Long employeeId, String identityCard, String fullName, String positionName) {}

    public record TrendPoint(LocalDate date, long records) {}
}
