package com.eatfood.control.service;

import com.eatfood.control.biometric.BiometricMatcher;
import com.eatfood.control.domain.*;
import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanService {

    private final BiometricMatcher matcher;
    private final DeviceService deviceService;
    private final EmployeeRepository employeeRepository;
    private final ConsumptionRepository consumptionRepository;
    private final ScheduleRepository scheduleRepository;
    private final MealTypeRepository mealTypeRepository;
    private final FailedScanRepository failedScanRepository;
    private final DeviceRepository deviceRepository;

    /**
     * Procesa un escaneo de huella y registra el consumo según las reglas de negocio.
     * Es idempotente respecto a {@code clientUuid} para soportar reintentos y sincronización offline.
     */
    @Transactional
    public ScanResponse scan(ScanRequest req) {
        log.debug("──── SCAN INICIO ─── clientUuid={}, offline={}, consumedAt={}",
                req.clientUuid(), req.offline(), req.consumedAt());

        Device device = deviceService.validateSession(req.sessionToken());
        Catering catering = device.getCatering();

        boolean offline = Boolean.TRUE.equals(req.offline());
        OffsetDateTime when = req.consumedAt() != null ? req.consumedAt() : OffsetDateTime.now();
        UUID clientUuid = req.clientUuid() != null ? req.clientUuid() : UUID.randomUUID();

        log.debug("[SCAN] deviceId={}, cateringId={}, when={}, clientUuid={}",
                device.getId(), catering.getId(), when, clientUuid);

        // Idempotencia: si ya existe ese registro, devolver éxito sin duplicar
        Optional<Consumption> existingByUuid = consumptionRepository.findByClientUuid(clientUuid);
        if (existingByUuid.isPresent()) {
            log.debug("[SCAN] IDEMPOTENTE → ya existe consumo con clientUuid={}", clientUuid);
            return success(existingByUuid.get());
        }

        // 1) Identificación biométrica 1:N
        byte[] template = decode(req.templateB64());
        Optional<BiometricMatcher.MatchResult> match = matcher.identify(template);
        if (match.isEmpty()) {
            log.debug("[SCAN] NOT_FOUND → huella no matchea con ningún empleado");
            registerFailed(catering.getId(), device.getId(), "NOT_FOUND", null, null);
            return new ScanResponse("NOT_FOUND", "HUELLA NO ENCONTRADA", null, null, when);
        }

        log.debug("[SCAN] MATCH → employeeId={}, score={}",
                match.get().employeeId(), match.get().score());

        Employee employee = employeeRepository.findById(match.get().employeeId()).orElse(null);
        if (employee == null || employee.isDeleted() || employee.getStatus() != EmployeeStatus.ACTIVE) {
            log.debug("[SCAN] NOT_ALLOWED → empleado inactivo o eliminado (id={})",
                    employee != null ? employee.getId() : "null");
            registerFailed(catering.getId(), device.getId(), "NOT_ALLOWED", null,
                    employee != null ? employee.getId() : null);
            return new ScanResponse("NOT_ALLOWED", "EMPLEADO INACTIVO", null, null, when);
        }

        log.debug("[SCAN] Empleado identificado: id={}, nombre='{}'", employee.getId(), employee.getFullName());

        // 2) Determinar tipo de comida (explícito o inferido por horario)
        MealType meal = resolveMealType(req.mealTypeCode(), when.toLocalTime());
        if (meal == null) {
            log.debug("[SCAN] OUT_OF_SCHEDULE → no se pudo resolver tipo de comida para hora={}", when.toLocalTime());
            registerFailed(catering.getId(), device.getId(), "OUT_OF_SCHEDULE", null, employee.getId());
            return new ScanResponse("OUT_OF_SCHEDULE", "FUERA DEL HORARIO PERMITIDO",
                    employee.getFullName(), null, when);
        }

        log.debug("[SCAN] MealType resuelto: id={}, code='{}', name='{}'", meal.getId(), meal.getCode(), meal.getName());

        // 3) Validar horario del tipo de comida resuelto
        Schedule schedule = scheduleRepository.findByMealTypeIdAndActiveTrue(meal.getId()).orElse(null);
        if (schedule == null || !schedule.contains(when.toLocalTime())) {
            log.debug("[SCAN] OUT_OF_SCHEDULE → schedule={}, hora={}",
                    schedule != null ? schedule.getStartTime() + "-" + schedule.getEndTime() : "null",
                    when.toLocalTime());
            registerFailed(catering.getId(), device.getId(), "OUT_OF_SCHEDULE", meal.getId(), employee.getId());
            return new ScanResponse("OUT_OF_SCHEDULE", "FUERA DEL HORARIO PERMITIDO",
                    employee.getFullName(), meal.getName(), when);
        }

        // 4) Validar permiso del empleado para ese tipo de comida
        if (!isMealAllowed(employee, meal)) {
            log.debug("[SCAN] NOT_ALLOWED → empleado '{}' no tiene permiso para '{}'",
                    employee.getFullName(), meal.getCode());
            registerFailed(catering.getId(), device.getId(), "NOT_ALLOWED", meal.getId(), employee.getId());
            return new ScanResponse("NOT_ALLOWED", "CONSUMO NO PERMITIDO",
                    employee.getFullName(), meal.getName(), when);
        }

        // 5) Antiduplicado por día de negocio
        LocalDate businessDate = when.toLocalDate();
        boolean duplicate = consumptionRepository.existsByEmployeeIdAndMealTypeIdAndBusinessDate(
                employee.getId(), meal.getId(), businessDate);
        log.debug("[SCAN] Antiduplicado: employeeId={}, mealTypeId={}, businessDate={}, ¿existe?={}",
                employee.getId(), meal.getId(), businessDate, duplicate);
        if (duplicate) {
            log.warn("[SCAN] ══ DUPLICATE ══ empleado='{}' (id={}), comida='{}' (id={}), fecha={}",
                    employee.getFullName(), employee.getId(), meal.getName(), meal.getId(), businessDate);
            registerFailed(catering.getId(), device.getId(), "DUPLICATE", meal.getId(), employee.getId());
            return new ScanResponse("DUPLICATE", meal.getName().toUpperCase() + " YA REGISTRADO",
                    employee.getFullName(), meal.getName(), when);
        }

        // 6) Registrar consumo
        Consumption consumption = Consumption.builder()
                .employee(employee)
                .catering(catering)
                .mealType(meal)
                .device(device)
                .consumedAt(when)
                .businessDate(businessDate)
                .offline(offline)
                .syncStatus(SyncStatus.SYNCED)
                .clientUuid(clientUuid)
                .build();
        consumption = consumptionRepository.save(consumption);
        log.info("[SCAN] ✓ SUCCESS → consumptionId={}, empleado='{}', comida='{}', fecha={}",
                consumption.getId(), employee.getFullName(), meal.getName(), businessDate);
        return success(consumption);
    }

    private ScanResponse success(Consumption c) {
        return new ScanResponse("SUCCESS", "REGISTRO EXITOSO",
                c.getEmployee().getFullName(), c.getMealType().getName(), c.getConsumedAt());
    }

    private MealType resolveMealType(String code, LocalTime time) {
        if (code != null && !code.isBlank()) {
            return mealTypeRepository.findByCode(code).orElse(null);
        }
        // Inferir por el horario activo que contenga la hora
        return scheduleRepository.findByActiveTrue().stream()
                .filter(s -> s.contains(time))
                .map(Schedule::getMealType)
                .findFirst()
                .orElse(null);
    }

    private boolean isMealAllowed(Employee employee, MealType meal) {
        return switch (meal.getCode()) {
            case "LUNCH" -> employee.isAllowsLunch();
            case "SNACK" -> employee.effectiveSnack();
            default -> true; // tipos de comida futuros: permitidos por defecto
        };
    }

    private void registerFailed(Long cateringId, Long deviceId, String reason, Long mealTypeId, Long employeeId) {
        failedScanRepository.save(FailedScan.builder()
                .cateringId(cateringId).deviceId(deviceId)
                .reason(reason).mealTypeId(mealTypeId).employeeId(employeeId)
                .build());
    }

    /** Feed de consumos del día para el Kiosk — no actualiza lastSeen del dispositivo. */
    @Transactional(readOnly = true)
    public List<TodayEntry> todayFeed(String sessionToken) {
        Device device = deviceRepository.findBySessionToken(sessionToken)
                .filter(Device::isConnected)
                .orElseThrow(() -> new BusinessException("INVALID_SESSION", "Sesión inválida."));
        return consumptionRepository
                .findByBusinessDateAndCateringId(LocalDate.now(), device.getCatering().getId())
                .stream()
                .sorted(Comparator.comparing(Consumption::getConsumedAt).reversed())
                .map(c -> new TodayEntry(
                        c.getEmployee().getFullName(),
                        c.getMealType().getName(),
                        c.getConsumedAt().toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))))
                .collect(Collectors.toList());
    }

    private byte[] decode(String b64) {
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_TEMPLATE", "Plantilla biométrica inválida.");
        }
    }
}
