package com.eatfood.control.service;

import com.eatfood.control.domain.Consumption;
import com.eatfood.control.domain.Employee;
import com.eatfood.control.domain.EmployeeStatus;
import com.eatfood.control.dto.ReportDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.repository.ConsumptionRepository;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.FailedScanRepository;
import com.eatfood.control.repository.FailedScanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReportService {

    /** Huso horario de negocio (Ecuador). Coincide con spring.jpa.properties.hibernate.jdbc.time_zone. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Guayaquil");

    /** Máximo de días permitido en un rango de reporte, para evitar consultas desmedidas. */
    private static final long MAX_RANGE_DAYS = 366;

    private final ConsumptionRepository consumptionRepository;
    private final EmployeeRepository employeeRepository;
    private final FailedScanRepository failedScanRepository;

    /** Valida que el rango de fechas sea coherente y no excesivamente amplio. */
    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException("INVALID_RANGE", "Debe indicar las fechas 'desde' y 'hasta'.");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("INVALID_RANGE", "La fecha 'desde' no puede ser posterior a 'hasta'.");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BusinessException("RANGE_TOO_LARGE",
                    "El rango de fechas no puede exceder " + MAX_RANGE_DAYS + " días.");
        }
    }

    @Transactional(readOnly = true)
    public List<ConsumptionRow> consumptions(LocalDate from, LocalDate to, Long restaurantId,
                                             Long employeeId) {
        validateRange(from, to);
        return consumptionRepository.report(from, to, restaurantId, employeeId).stream()
                .map(this::toRow).toList();
    }

    private ConsumptionRow toRow(Consumption c) {
        Employee e = c.getEmployee();
        return new ConsumptionRow(
                c.getId(), c.getBusinessDate(), c.getConsumedAt(),
                e.getFullName(), e.getIdentityCard(),
                c.getRestaurant().getName(), c.getMealName(),
                c.getObservation(), c.isOffline());
    }

    @Transactional(readOnly = true)
    public DashboardStats dashboard(LocalDate date) {
        if (date == null) date = LocalDate.now(BUSINESS_ZONE);

        long total = consumptionRepository.countByBusinessDate(date);
        long desayunos = consumptionRepository.countByBusinessDateAndMealName(date, "Desayuno");
        long meriendas = consumptionRepository.countByBusinessDateAndMealName(date, "Merienda");

        long expected = employeeRepository.countByDeletedFalseAndStatus(EmployeeStatus.ACTIVE);
        long consumed = consumptionRepository.countDistinctEmployees(date);
        long pending = Math.max(0, expected - consumed);
        double pct = expected == 0 ? 0 : Math.round((consumed * 10000.0 / expected)) / 100.0;

        OffsetDateTime start = date.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
        OffsetDateTime end = date.plusDays(1).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
        List<com.eatfood.control.domain.FailedScan> failed = failedScanRepository.between(start, end);
        long notFound = failed.stream().filter(f -> "NOT_FOUND".equals(f.getReason())).count();
        long outOfSchedule = failed.stream().filter(f -> "OUT_OF_SCHEDULE".equals(f.getReason())).count();

        return new DashboardStats(date, total, desayunos, meriendas,
                expected, consumed, pending, pct, notFound, outOfSchedule);
    }

    @Transactional(readOnly = true)
    public List<EmployeeNotConsumed> notConsumed(LocalDate date) {
        if (date == null) date = LocalDate.now(BUSINESS_ZONE);
        return employeeRepository.findActiveNotConsumed(EmployeeStatus.ACTIVE, date).stream()
                .map(e -> new EmployeeNotConsumed(e.getId(), e.getIdentityCard(), e.getFullName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> trend(LocalDate from, LocalDate to) {
        validateRange(from, to);
        // Una sola consulta agrupada por día; los días sin consumos se rellenan con cero.
        Map<LocalDate, Long> counts = new HashMap<>();
        for (Object[] row : consumptionRepository.countGroupedByBusinessDate(from, to)) {
            counts.put((LocalDate) row[0], (Long) row[1]);
        }
        List<TrendPoint> points = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            points.add(new TrendPoint(d, counts.getOrDefault(d, 0L)));
        }
        return points;
    }
}
