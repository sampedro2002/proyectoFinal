package com.eatfood.control.service;

import com.eatfood.control.domain.Consumption;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Fechas y trazabilidad del registro administrativo; no afecta al escaneo por huella. */
@Service
@RequiredArgsConstructor
public class ManualConsumptionPolicy {
    public static final ZoneId ZONE = ZoneId.of("America/Guayaquil");
    private final ScheduleRepository scheduleRepository;
    private final AuditService auditService;

    public OffsetDateTime resolve(LocalDate date, LocalTime time, Boolean contingency,
                                  String reason, Consumption existing) {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        LocalDate selected = date != null ? date
                : existing != null ? existing.getBusinessDate() : now.toLocalDate();
        boolean exceptional = Boolean.TRUE.equals(contingency) || !selected.equals(now.toLocalDate())
                || (existing != null && !existing.getBusinessDate().equals(now.toLocalDate()));
        if ((exceptional || existing != null) && (reason == null || reason.isBlank())) {
            throw new BusinessException("REASON_REQUIRED", "Indique el motivo del registro o de la corrección.");
        }
        if (reason != null && reason.length() > 500) {
            throw new BusinessException("INVALID_REASON", "El motivo no puede superar 500 caracteres.");
        }
        if (existing == null && !selected.equals(now.toLocalDate()) && time == null) {
            throw new BusinessException("TIME_REQUIRED", "Indique la hora del consumo para la fecha seleccionada.");
        }
        if (!Boolean.TRUE.equals(contingency)) {
            var schedule = scheduleRepository.findFirstByOrderByIdAsc().orElse(null);
            if (schedule == null || !schedule.isActive() || !schedule.contains(now.toLocalTime())) {
                throw new BusinessException("OUT_OF_SCHEDULE",
                        "Fuera del horario permitido. Para cargar o corregir el control en papel, active Registro por contingencia e indique el motivo.");
            }
        }
        LocalTime selectedTime = time != null ? time : existing != null
                ? existing.getConsumedAt().atZoneSameInstant(ZONE).toLocalTime() : now.toLocalTime();
        return selected.atTime(selectedTime).atZone(ZONE).toOffsetDateTime();
    }

    public void audit(Consumption c, String action, String before, Boolean contingency, String reason) {
        auditService.record("Consumption", String.valueOf(c.getId()), action, before,
                snapshot(c) + "|contingencia=" + Boolean.TRUE.equals(contingency)
                        + "|motivo=" + (reason == null ? "" : reason.trim()));
    }

    public String snapshot(Consumption c) {
        return "titular=" + (c.getEmployee() != null ? "emp:" + c.getEmployee().getId()
                : "ext:" + c.getExternalPerson().getId())
                + "|proxy=" + (c.getProxyEmployee() != null ? "emp:" + c.getProxyEmployee().getId()
                : c.getProxyExternalPerson() != null ? "ext:" + c.getProxyExternalPerson().getId() : "")
                + "|rest=" + c.getRestaurant().getId() + "|comida=" + c.getMealName()
                + "|fecha=" + c.getBusinessDate() + "|hora=" + c.getConsumedAt()
                + "|creado=" + c.getCreatedAt() + "|cancelado=" + c.isCancelled()
                + "|observacion=" + c.getObservation();
    }
}
