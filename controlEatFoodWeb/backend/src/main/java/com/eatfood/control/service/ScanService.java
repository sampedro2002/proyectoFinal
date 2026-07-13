package com.eatfood.control.service;

import com.eatfood.control.biometric.BiometricMatcher;
import com.eatfood.control.domain.*;
import com.eatfood.control.dto.ReportDtos.ConsumptionRow;
import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanService {

    /** Huso horario de negocio (Ecuador). Coincide con spring.jpa.properties.hibernate.jdbc.time_zone. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Guayaquil");

    /** Mismo esquema de código público que EmployeeService (la columna public_code es NOT NULL). */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final java.security.SecureRandom CODE_RANDOM = new java.security.SecureRandom();

    private final BiometricMatcher matcher;
    private final DeviceService deviceService;
    private final EmployeeRepository employeeRepository;
    private final ConsumptionRepository consumptionRepository;
    private final ScheduleRepository scheduleRepository;
    private final FailedScanRepository failedScanRepository;
    private final RestaurantRepository restaurantRepository;

    /**
     * Procesa un escaneo de huella y registra el consumo según las reglas de negocio.
     * Es idempotente respecto a {@code clientUuid} para soportar reintentos y sincronización offline.
     */
    @Transactional
    public ScanResponse scan(ScanRequest req) {
        log.debug("──── SCAN INICIO ─── clientUuid={}, offline={}, consumedAt={}",
                req.clientUuid(), req.offline(), req.consumedAt());

        Device device = deviceService.validateSession(req.sessionToken());
        Restaurant restaurant = device.getRestaurant();

        boolean offline = Boolean.TRUE.equals(req.offline());
        OffsetDateTime when = req.consumedAt() != null ? req.consumedAt() : OffsetDateTime.now(BUSINESS_ZONE);
        UUID clientUuid = req.clientUuid() != null ? req.clientUuid() : UUID.randomUUID();

        log.debug("[SCAN] deviceId={}, restaurantId={}, when={}, clientUuid={}",
                device.getId(), restaurant.getId(), when, clientUuid);

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
            registerFailed(restaurant.getId(), device.getId(), "NOT_FOUND", null);
            return new ScanResponse("NOT_FOUND", "HUELLA NO ENCONTRADA", null, null, null, when);
        }

        log.debug("[SCAN] MATCH → employeeId={}, score={}",
                match.get().employeeId(), match.get().score());

        // Bloqueo pesimista: serializa escaneos concurrentes del mismo empleado (p. ej. dos
        // dispositivos o un doble toque) para que el tope diario de comidas se respete siempre.
        Employee employee = employeeRepository.findByIdForUpdate(match.get().employeeId()).orElse(null);
        if (employee == null || employee.isDeleted() || employee.getStatus() != EmployeeStatus.ACTIVE) {
            log.debug("[SCAN] NOT_ALLOWED → empleado inactivo o eliminado (id={})",
                    employee != null ? employee.getId() : "null");
            registerFailed(restaurant.getId(), device.getId(), "NOT_ALLOWED",
                    employee != null ? employee.getId() : null);
            return new ScanResponse("NOT_ALLOWED", "EMPLEADO INACTIVO", null, null, null, when);
        }

        log.debug("[SCAN] Empleado identificado: id={}, nombre='{}'", employee.getId(), employee.getFullName());

        // 2) Determinar la comida a registrar basada en el orden (Desayuno/Almuerzo).
        // Se convierte SIEMPRE a BUSINESS_ZONE antes de leer fecha/hora: `when` puede traer
        // cualquier offset (dispositivo offline con reloj/zona distinta, backend con TZ de
        // JVM distinta a Ecuador), y calcular la fecha/hora de negocio con el offset "tal
        // cual venía" desalinea el tope diario y el chequeo de horario respecto al resto del
        // sistema (dashboard, feed del kiosk), que sí usan BUSINESS_ZONE de forma consistente.
        var whenBusiness = when.atZoneSameInstant(BUSINESS_ZONE);
        LocalDate businessDate = whenBusiness.toLocalDate();
        MealSelection selection = resolveMealForScan(employee, whenBusiness.toLocalTime(), businessDate);
        if (selection.status() != null && !"OK".equals(selection.status())) {
            log.debug("[SCAN] {} → empleado='{}', hora={}",
                    selection.failReason(), employee.getFullName(), when.toLocalTime());
            registerFailed(restaurant.getId(), device.getId(), selection.failReason(), employee.getId());
            return new ScanResponse(selection.status(), selection.message(),
                    employee.getFullName(), null, null, when);
        }

        String mealName = selection.mealName();

        // 3) Registrar consumo
        Consumption consumption = Consumption.builder()
                .employee(employee)
                .restaurant(restaurant)
                .device(device)
                .consumedAt(when)
                .businessDate(businessDate)
                .offline(offline)
                .syncStatus(SyncStatus.SYNCED)
                .mealName(mealName)
                .clientUuid(clientUuid)
                .build();
        try {
            consumption = consumptionRepository.save(consumption);
        } catch (DataIntegrityViolationException ex) {
            log.warn("[SCAN] DataIntegrityViolation al persistir consumo (clientUuid={}, empleado={}) — " +
                    "se resuelve como idempotente/DUPLICATE.", clientUuid, employee.getId());
            Consumption existing = consumptionRepository
                    .findByClientUuid(clientUuid)
                    .orElseGet(() -> consumptionRepository
                            .findFirstByEmployeeIdAndBusinessDate(employee.getId(), businessDate)
                            .orElse(null));
            if (existing != null) {
                return success(existing);
            }
            throw ex;
        }
        log.info("[SCAN] ✓ SUCCESS → consumptionId={}, empleado='{}', fecha={}, comida={}",
                consumption.getId(), employee.getFullName(), businessDate, selection.mealName());
        return success(consumption);
    }

    private ScanResponse success(Consumption c) {
        return new ScanResponse("SUCCESS", "REGISTRO EXITOSO",
                c.getEmployee().getFullName(), c.getMealName(), 1, c.getConsumedAt());
    }

    private record MealSelection(String status, String message, String failReason, String mealName) {
        static MealSelection ok(String mealName) { return new MealSelection("OK", null, null, mealName); }
        static MealSelection fail(String status, String message, String failReason) {
            return new MealSelection(status, message, failReason, null);
        }
    }

    /**
     * Determina si el empleado puede retirar un plato más en el día.
     */
    private MealSelection resolveMealForScan(Employee employee, LocalTime now, LocalDate date) {
        Schedule sch = scheduleRepository.findFirstByOrderByIdAsc().orElse(null);
        if (sch == null || !sch.isActive() || !sch.contains(now)) {
            return MealSelection.fail("OUT_OF_SCHEDULE", "FUERA DEL HORARIO PERMITIDO", "OUT_OF_SCHEDULE");
        }

        // Se decide por las comidas YA registradas hoy (no solo por el conteo), para que un
        // empleado autorizado únicamente a almuerzo (allowsLunch=false, allowsSnack=true) no
        // reciba "Desayuno" en su primer escaneo y luego otro "Almuerzo" en el segundo: su
        // tope diario es 1 comida, no 2.
        // Nota de nomenclatura: allows_lunch gobierna el "Desayuno" (primer plato) y
        // allows_snack el "Almuerzo" (segundo plato); son los dos únicos platos del sistema.
        List<String> consumedToday = consumptionRepository.findMealNamesByEmployeeIdAndBusinessDate(employee.getId(), date);
        boolean hadBreakfast = consumedToday.contains("Desayuno");
        boolean hadLunch = consumedToday.contains("Almuerzo");

        if (!hadBreakfast && employee.isAllowsLunch()) {
            return MealSelection.ok("Desayuno");
        }
        if (!hadLunch && employee.effectiveSnack()) {
            return MealSelection.ok("Almuerzo");
        }
        if (consumedToday.isEmpty()) {
            return MealSelection.fail("NOT_ALLOWED", "CONSUMO NO PERMITIDO", "NOT_ALLOWED");
        }
        return MealSelection.fail("DUPLICATE",
                consumedToday.size() >= 2 ? "LÍMITE ALCANZADO" : "CONSUMO YA REGISTRADO", "DUPLICATE");
    }

    private void registerFailed(Long restaurantId, Long deviceId, String reason, Long employeeId) {
        failedScanRepository.save(FailedScan.builder()
                .restaurantId(restaurantId).deviceId(deviceId)
                .reason(reason).employeeId(employeeId)
                .build());
    }

    /**
     * Feed de consumos del día para el Kiosk. Valida la sesión y actúa como
     * heartbeat: al refrescarse periódicamente mantiene viva (lastSeen) la sesión
     * del dispositivo en uso, evitando que el TTL por inactividad la expire.
     * También devuelve el nombre actualizado del restaurante para que el cliente
     * pueda reflejar cambios sin necesidad de reconectar.
     */
    @Transactional
    public TodayFeedResponse todayFeed(String sessionToken) {
        Device device = deviceService.validateSession(sessionToken);
        List<TodayEntry> entries = consumptionRepository
                .findByBusinessDateAndRestaurantId(LocalDate.now(BUSINESS_ZONE), device.getRestaurant().getId())
                .stream()
                .sorted(Comparator.comparing(Consumption::getConsumedAt).reversed())
                .map(c -> new TodayEntry(
                        c.getEmployee().getFullName(),
                        c.getMealName(),
                        c.getConsumedAt().toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))))
                .collect(Collectors.toList());
        return new TodayFeedResponse(device.getRestaurant().getName(), entries);
    }

    @Transactional
    public KioskReport todayReport(String sessionToken) {
        Device device = deviceService.validateSession(sessionToken);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<Consumption> consumptions = consumptionRepository
                .findByBusinessDateAndRestaurantIdWithDetails(today, device.getRestaurant().getId());

        List<ConsumptionRow> rows = consumptions.stream()
                .map(c -> {
                    Employee e = c.getEmployee();
                    return new ConsumptionRow(
                            c.getId(), c.getBusinessDate(), c.getConsumedAt(),
                            e.getFullName(), e.getIdentityCard(),
                            c.getRestaurant().getName(), c.getMealName(),
                            c.getObservation(), c.isOffline());
                })
                .toList();

        long desayunos = consumptions.stream().filter(c -> "Desayuno".equals(c.getMealName())).count();
        long almuerzos = consumptions.stream().filter(c -> "Almuerzo".equals(c.getMealName())).count();
        long general = consumptions.stream().filter(c -> !"Desayuno".equals(c.getMealName()) && !"Almuerzo".equals(c.getMealName())).count();

        Map<String, Long> plateCounts = new LinkedHashMap<>();
        plateCounts.put("Desayunos", desayunos);
        plateCounts.put("Almuerzos", almuerzos);
        if (general > 0) plateCounts.put("Otros", general);

        return new KioskReport(
                device.getRestaurant().getName(),
                today,
                rows,
                plateCounts);
    }

    public record KioskReport(
            String restaurantName,
            LocalDate date,
            List<ConsumptionRow> rows,
            Map<String, Long> plateCounts) {}

    @Transactional
    public ManualScanResponse manualScan(ManualScanRequest req) {
        log.info("[MANUAL] Registro manual: employeeId={}, restaurantId={}",
                req.employeeId(), req.restaurantId());

        Employee employee = employeeRepository.findById(req.employeeId())
                .orElse(null);
        if (employee == null || employee.isDeleted()) {
            return new ManualScanResponse("NOT_FOUND", "Empleado no encontrado", null, null);
        }

        Restaurant restaurant = restaurantRepository.findById(req.restaurantId()).orElse(null);
        if (restaurant == null) {
            return new ManualScanResponse("ERROR", "Restaurant no encontrado",
                    employee.getFullName(), null);
        }

        LocalDate businessDate = LocalDate.now(BUSINESS_ZONE);
        String mealName = mealNameForCode(req.mealTypeCode());

        Consumption consumption = Consumption.builder()
                .employee(employee)
                .restaurant(restaurant)
                .consumedAt(OffsetDateTime.now(BUSINESS_ZONE))
                .businessDate(businessDate)
                .observation(blankToNull(req.observation()))
                .offline(false)
                .syncStatus(SyncStatus.SYNCED)
                .mealName(mealName)
                .clientUuid(UUID.randomUUID())
                .build();
        consumptionRepository.save(consumption);

        log.info("[MANUAL] ✓ SUCCESS → empleado='{}', restaurant='{}', comida='{}'",
                employee.getFullName(), restaurant.getName(), mealName);
        return new ManualScanResponse("SUCCESS", "REGISTRO EXITOSO",
                employee.getFullName(), mealName);
    }

    /**
     * Traduce el código de tipo de comida al nombre usado en los reportes.
     * Solo existen dos platos: Desayuno (BREAKFAST) y Almuerzo (LUNCH).
     * SNACK se acepta por compatibilidad con clientes antiguos y equivale a Almuerzo.
     */
    private static String mealNameForCode(String mealTypeCode) {
        if ("LUNCH".equalsIgnoreCase(mealTypeCode) || "SNACK".equalsIgnoreCase(mealTypeCode)) {
            return "Almuerzo";
        }
        return "Desayuno";
    }

    /**
     * Registra un consumo para una persona externa (no empleada). Crea (o reutiliza)
     * un empleado con status=INACTIVE para que el consumo quede referenciado y
     * aparezca en el feed del kiosk y en reportes, pero no aparezca en la gestión
     * de empleados activos ni en "pendientes del día".
     */
    @Transactional
    public ManualScanResponse registerExternal(ExternalScanRequest req) {
        log.info("[EXTERNAL] Registro externo: cedula='{}', nombre='{}', restaurantId={}",
                req.identityCard(), req.fullName(), req.restaurantId());

        // Validación flexible del documento: si tiene forma de cédula ecuatoriana
        // (10 dígitos) debe ser válida; otros formatos (pasaporte de un visitante
        // extranjero, documento alfanumérico) se aceptan tal cual.
        String identityCard = req.identityCard().trim();
        boolean isPassport = Boolean.TRUE.equals(req.isPassport());
        if (!isPassport && !com.eatfood.control.util.CedulaValidator.isValid(identityCard)) {
            throw new BusinessException("INVALID_CARD",
                    "La cédula ingresada no es una cédula ecuatoriana válida.");
        }

        Restaurant restaurant = restaurantRepository.findById(req.restaurantId()).orElse(null);
        if (restaurant == null) {
            return new ManualScanResponse("ERROR", "Restaurant no encontrado", req.fullName(), null);
        }

        Employee employee = employeeRepository.findByIdentityCardAndDeletedFalse(identityCard)
                .orElseGet(() -> {
                    Employee e = new Employee();
                    e.setIdentityCard(identityCard);
                    e.setFullName(req.fullName());
                    e.setStatus(EmployeeStatus.INACTIVE);
                    e.setAllowsLunch(true);
                    e.setAllowsSnack(true);
                    e.setPublicCode(generatePublicCode());
                    return e;
                });
        if (employee.getId() == null) {
            employee = employeeRepository.save(employee);
            log.info("[EXTERNAL] Empleado externo creado: id={}, cedula='{}', nombre='{}'",
                    employee.getId(), employee.getIdentityCard(), employee.getFullName());
        }

        LocalDate businessDate = LocalDate.now(BUSINESS_ZONE);
        String mealName = mealNameForCode(req.mealTypeCode());
        Consumption consumption = Consumption.builder()
                .employee(employee)
                .restaurant(restaurant)
                .consumedAt(OffsetDateTime.now(BUSINESS_ZONE))
                .businessDate(businessDate)
                .observation(blankToNull(req.observation()))
                .offline(false)
                .syncStatus(SyncStatus.SYNCED)
                .mealName(mealName)
                .clientUuid(UUID.randomUUID())
                .build();
        consumptionRepository.save(consumption);

        log.info("[EXTERNAL] ✓ SUCCESS → nombre='{}', restaurant='{}', comida='{}'",
                employee.getFullName(), restaurant.getName(), mealName);
        return new ManualScanResponse("SUCCESS", "REGISTRO EXITOSO",
                employee.getFullName(), mealName);
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /** Genera un código público único EMP-XXXXXX reintentando ante colisión. */
    private String generatePublicCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder("EMP-");
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET.charAt(CODE_RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            String code = sb.toString();
            if (!employeeRepository.existsByPublicCode(code)) return code;
        }
        throw new BusinessException("CODE_GENERATION", "No se pudo generar un código único de empleado.");
    }

    private byte[] decode(String b64) {
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_TEMPLATE", "Plantilla biométrica inválida.");
        }
    }
}
