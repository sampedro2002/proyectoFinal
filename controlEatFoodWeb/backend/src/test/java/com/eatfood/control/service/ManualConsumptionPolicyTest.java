package com.eatfood.control.service;

import com.eatfood.control.domain.Schedule;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.repository.ScheduleRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualConsumptionPolicyTest {
    private final ScheduleRepository schedules = mock(ScheduleRepository.class);
    private final ManualConsumptionPolicy policy = new ManualConsumptionPolicy(schedules, mock(AuditService.class));

    @Test
    void contingencyAllowsPaperEntryOutsideSchedule() {
        LocalDate date = LocalDate.of(2026, 1, 15);
        var when = policy.resolve(date, LocalTime.of(12, 45), true, "Corte de luz", null);
        assertThat(when.toLocalDate()).isEqualTo(date);
        assertThat(when.toLocalTime()).isEqualTo(LocalTime.of(12, 45));
        assertThat(when.getOffset().getTotalSeconds()).isEqualTo(-5 * 3600);
        verifyNoInteractions(schedules);
    }

    @Test
    void contingencyRequiresNonblankReason() {
        assertThatThrownBy(() -> policy.resolve(null, null, true, "  ", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("motivo");
    }

    @Test
    void backdatingRequiresTimeAndReason() {
        LocalDate past = LocalDate.now(ManualConsumptionPolicy.ZONE).minusDays(2);
        assertThatThrownBy(() -> policy.resolve(past, LocalTime.NOON, true, null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("motivo");
        assertThatThrownBy(() -> policy.resolve(past, null, true, "Papel", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("hora");
    }

    @Test
    void changingDateAloneDoesNotBypassSchedule() {
        when(schedules.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        LocalDate future = LocalDate.now(ManualConsumptionPolicy.ZONE).plusDays(2);
        assertThatThrownBy(() -> policy.resolve(future, LocalTime.NOON, false, "Corrección", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Fuera del horario");
    }

    @Test
    void legacyRequestStillDefaultsToTodayDuringSchedule() {
        when(schedules.findFirstByOrderByIdAsc()).thenReturn(Optional.of(
                Schedule.builder().startTime(LocalTime.MIN).endTime(LocalTime.MAX).active(true).build()));
        assertThat(policy.resolve(null, null, null, null, null).toLocalDate())
                .isEqualTo(LocalDate.now(ManualConsumptionPolicy.ZONE));
    }
}
