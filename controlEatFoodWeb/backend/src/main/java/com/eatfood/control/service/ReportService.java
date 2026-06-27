package com.eatfood.control.service;

import com.eatfood.control.domain.Consumption;
import com.eatfood.control.domain.Employee;
import com.eatfood.control.domain.EmployeeStatus;
import com.eatfood.control.dto.ReportDtos.*;
import com.eatfood.control.repository.ConsumptionRepository;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.FailedScanRepository;
import com.eatfood.control.repository.MealTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReportService {

    /** Huso horario de negocio (Ecuador). Coincide con spring.jpa.properties.hibernate.jdbc.time_zone. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Guayaquil");

    private final ConsumptionRepository consumptionRepository;
    private final EmployeeRepository employeeRepository;
    private final MealTypeRepository mealTypeRepository;
    private final FailedScanRepository failedScanRepository;

    @Transactional(readOnly = true)
    public List<ConsumptionRow> consumptions(LocalDate from, LocalDate to, Long cateringId,
                                             Long mealTypeId, Long employeeId) {
        return consumptionRepository.report(from, to, cateringId, mealTypeId, employeeId).stream()
                .map(this::toRow).toList();
    }

    private ConsumptionRow toRow(Consumption c) {
        Employee e = c.getEmployee();
        return new ConsumptionRow(
                c.getId(), c.getBusinessDate(), c.getConsumedAt(),
                e.getFullName(), e.getIdentityCard(),
                e.getPosition() != null ? e.getPosition().getName() : null,
                c.getCatering().getName(), c.getMealType().getName(),
                c.isOffline());
    }

    @Transactional(readOnly = true)
    public DashboardStats dashboard(LocalDate date) {
        if (date == null) date = LocalDate.now(BUSINESS_ZONE);

        Long lunchId = mealTypeRepository.findByCode("LUNCH").map(m -> m.getId()).orElse(null);
        Long snackId = mealTypeRepository.findByCode("SNACK").map(m -> m.getId()).orElse(null);

        long total = consumptionRepository.countByBusinessDate(date);
        long lunch = lunchId != null ? consumptionRepository.countByBusinessDateAndMealTypeId(date, lunchId) : 0;
        long snack = snackId != null ? consumptionRepository.countByBusinessDateAndMealTypeId(date, snackId) : 0;

        long expected = employeeRepository.countByDeletedFalseAndStatus(EmployeeStatus.ACTIVE);
        long consumed = consumptionRepository.countDistinctEmployees(date);
        long pending = Math.max(0, expected - consumed);
        double pct = expected == 0 ? 0 : Math.round((consumed * 10000.0 / expected)) / 100.0;

        OffsetDateTime start = date.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
        OffsetDateTime end = date.plusDays(1).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
        List<com.eatfood.control.domain.FailedScan> failed = failedScanRepository.between(start, end);
        long notFound = failed.stream().filter(f -> "NOT_FOUND".equals(f.getReason())).count();
        long outOfSchedule = failed.stream().filter(f -> "OUT_OF_SCHEDULE".equals(f.getReason())).count();

        return new DashboardStats(date, total, lunch, snack,
                expected, consumed, pending, pct, notFound, outOfSchedule);
    }

    @Transactional(readOnly = true)
    public List<EmployeeNotConsumed> notConsumed(LocalDate date) {
        if (date == null) date = LocalDate.now(BUSINESS_ZONE);
        Set<Long> consumed = new HashSet<>(consumptionRepository.findConsumedEmployeeIds(date));
        List<EmployeeNotConsumed> result = new ArrayList<>();
        for (Employee e : employeeRepository.findByDeletedFalseAndStatus(EmployeeStatus.ACTIVE)) {
            if (!consumed.contains(e.getId())) {
                result.add(new EmployeeNotConsumed(e.getId(), e.getIdentityCard(), e.getFullName(),
                        e.getPosition() != null ? e.getPosition().getName() : null));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> trend(LocalDate from, LocalDate to) {
        List<TrendPoint> points = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long records = consumptionRepository.countByBusinessDate(d);
            points.add(new TrendPoint(d, records));
        }
        return points;
    }
}
