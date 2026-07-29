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

    private final BiometricMatcher matcher;
    private final DeviceService deviceService;
    private final EmployeeRepository employeeRepository;
    private final ExternalPersonRepository externalPersonRepository;
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

        // 2) Determinar la comida a registrar basada en el orden (Almuerzo/Merienda).
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
                .method(Method.FINGERPRINT)
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
        // empleado autorizado únicamente al segundo plato (permiteAlmuerzo=false, permiteMerienda=true)
        // no reciba "Almuerzo" en su primer escaneo y luego otra "Merienda" en el segundo: su
        // tope diario es 1 comida, no 2.
        // Nota de nomenclatura: permite_almuerzo gobierna el "Almuerzo" (primer plato) y
        // permite_merienda la "Merienda" (segundo plato); son los dos únicos platos del sistema.
        List<String> consumedToday = consumptionRepository.findMealNamesByEmployeeIdAndBusinessDate(employee.getId(), date);
        boolean hadBreakfast = consumedToday.contains("Almuerzo");
        boolean hadLunch = consumedToday.contains("Merienda");

        if (!hadBreakfast && employee.isAllowsLunch()) {
            return MealSelection.ok("Almuerzo");
        }
        if (!hadLunch && employee.effectiveSnack()) {
            return MealSelection.ok("Merienda");
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
                        c.titularName(),
                        c.getMealName(),
                        c.getConsumedAt().toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                        c.getMethod() != null ? c.getMethod().name() : "FINGERPRINT",
                        c.proxyName(),
                        c.proxyIsExternal()))
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
                .map(c -> new ConsumptionRow(
                        c.getId(), c.getBusinessDate(), c.getConsumedAt(),
                        c.titularName(), c.titularIdentityCard(),
                        c.getRestaurant().getName(), c.getMealName(),
                        c.getObservation(), c.isOffline(),
                        c.getMethod() != null ? c.getMethod().name() : Method.FINGERPRINT.name(),
                        c.proxyName(),
                        c.proxyIsExternal(),
                        c.isCancelled()))
                .toList();

        long almuerzos = consumptions.stream().filter(c -> "Almuerzo".equals(c.getMealName())).count();
        long meriendas = consumptions.stream().filter(c -> "Merienda".equals(c.getMealName())).count();
        long general = consumptions.stream().filter(c -> !"Almuerzo".equals(c.getMealName()) && !"Merienda".equals(c.getMealName())).count();

        Map<String, Long> plateCounts = new LinkedHashMap<>();
        plateCounts.put("Almuerzos", almuerzos);
        plateCounts.put("Meriendas", meriendas);
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

    /**
     * Registro manual de tipo "retira por otro" (solo ADMIN).
     *
     * Quien retira es un empleado ({@code proxyEmployeeId}) o una persona externa
     * ya registrada ({@code proxyExternalPersonId}) — exactamente uno de los dos —
     * y lo hace a nombre de una lista de titulares empleados (Juan, Luis, Maria...).
     * Para cada titular se crea una fila de {@code consumption} por cada codigo de
     * comida pedido, con {@code method='MANUAL'}, el apoderado correspondiente y
     * {@code observacion="X retira de Y"} autogenerada (el admin no necesita
     * capturarla en la UI). No se valida horario (es un override administrativo),
     * pero sí el permiso del titular por comida y el duplicado del día: no se
     * registra un plato no permitido ni uno ya consumido hoy.
     */
    @Transactional
    public ManualScanResponse manualScan(ManualScanRequest req) {
        log.info("[MANUAL] Retira-por-otro: proxyEmployeeId={}, proxyExternalPersonId={}, restaurantId={}, titulares={}",
                req.proxyEmployeeId(), req.proxyExternalPersonId(), req.restaurantId(),
                req.titulars() != null ? req.titulars().size() : 0);

        if (req.titulars() == null || req.titulars().isEmpty()) {
            return new ManualScanResponse("ERROR",
                    "Debe indicar al menos un titular", null, null, 0);
        }

        // Quien retira: empleado interno ACTIVO o persona externa registrada.
        // Exactamente uno de los dos (el CHECK de la BD también lo garantiza).
        boolean hasEmpProxy = req.proxyEmployeeId() != null;
        boolean hasExtProxy = req.proxyExternalPersonId() != null;
        if (hasEmpProxy == hasExtProxy) {
            return new ManualScanResponse("ERROR",
                    "Debe indicar quién retira: un empleado o una persona externa registrada (solo uno).",
                    null, null, 0);
        }

        Employee proxy = null;
        ExternalPerson proxyExt = null;
        if (hasEmpProxy) {
            proxy = employeeRepository.findById(req.proxyEmployeeId()).orElse(null);
            if (proxy == null || proxy.isDeleted()) {
                return new ManualScanResponse("NOT_FOUND",
                        "Empleado que retira no encontrado", null, null, 0);
            }
            if (proxy.getStatus() != com.eatfood.control.domain.EmployeeStatus.ACTIVE) {
                return new ManualScanResponse("ERROR",
                        "El empleado que retira está inactivo y no puede realizar registros manuales.", proxy.getFullName(), null, 0);
            }
        } else {
            proxyExt = externalPersonRepository.findById(req.proxyExternalPersonId()).orElse(null);
            if (proxyExt == null) {
                return new ManualScanResponse("NOT_FOUND",
                        "La persona externa que retira no está registrada.", null, null, 0);
            }
        }
        final String proxyName = proxy != null ? proxy.getFullName() : proxyExt.getFullName();

        Restaurant restaurant = restaurantRepository.findById(req.restaurantId())
                .orElse(null);
        if (restaurant == null) {
            return new ManualScanResponse("ERROR", "Restaurant no encontrado",
                    proxyName, null, 0);
        }

        LocalDate businessDate = LocalDate.now(BUSINESS_ZONE);
        OffsetDateTime now = OffsetDateTime.now(BUSINESS_ZONE);

        Schedule sch = scheduleRepository.findFirstByOrderByIdAsc().orElse(null);
        if (sch == null || !sch.isActive() || !sch.contains(now.toLocalTime())) {
            return new ManualScanResponse("OUT_OF_SCHEDULE",
                    "Fuera del horario permitido para registros manuales", proxyName, null, 0);
        }

        int created = 0;
        String lastMealName = null;
        // Motivos por los que se omitió un plato (no permitido / ya registrado), para
        // devolverlos en el mensaje y que la UI explique qué no se creó y por qué.
        List<String> skipped = new ArrayList<>();

        final Employee proxyEmp = proxy;
        final ExternalPerson proxyExternal = proxyExt;

        // El proxy empleado no puede ser titular de sí mismo (el proxy externo se
        // valida por cédula dentro del bucle, al conocer al titular)
        if (proxyEmp != null) {
            boolean proxyIsAlsoTitular = req.titulars().stream()
                    .anyMatch(item -> proxyEmp.getId().equals(item.employeeId()));
            if (proxyIsAlsoTitular) {
                return new ManualScanResponse("ERROR",
                        "El empleado que retira no puede ser al mismo tiempo titular de sí mismo.",
                        proxyName, null, 0);
            }
        }
        for (ManualScanItem item : req.titulars()) {
            // Lock pesimista igual que el escaneo por huella: serializa registros
            // concurrentes del mismo empleado (manual + kiosco a la vez) para que el
            // chequeo "ya consumió este plato hoy" no compita con otra transacción.
            Employee titular = employeeRepository.findByIdForUpdate(item.employeeId())
                    .orElse(null);
            if (titular == null || titular.isDeleted()) {
                skipped.add("Empleado ID " + item.employeeId() + " no encontrado");
                continue;
            }
            if (titular.getStatus() != com.eatfood.control.domain.EmployeeStatus.ACTIVE) {
                skipped.add(titular.getFullName() + ": Empleado inactivo");
                continue;
            }
            // Quien retira (persona externa) no puede ser la misma persona que el titular
            if (proxyExternal != null && proxyExternal.getIdentityCard().equals(titular.getIdentityCard())) {
                skipped.add(titular.getFullName() + ": quien retira no puede ser el mismo titular");
                continue;
            }
            if (item.mealTypeCodes() == null || item.mealTypeCodes().isEmpty()) {
                continue;
            }
            // Comidas ya registradas hoy para este titular. Se usa un Set mutable para
            // también bloquear duplicados dentro de la MISMA petición (código repetido).
            Set<String> consumedToday = new HashSet<>(
                    consumptionRepository.findMealNamesByEmployeeIdAndBusinessDate(titular.getId(), businessDate));
            String observation = proxyName + " retira de " + titular.getFullName();
            for (String code : item.mealTypeCodes()) {
                String mealName = mealNameForCode(code);
                // Permiso del empleado: Merienda requiere allowsSnack; Almuerzo, allowsLunch.
                boolean allowed = "Merienda".equals(mealName) ? titular.effectiveSnack() : titular.isAllowsLunch();
                if (!allowed) {
                    skipped.add(titular.getFullName() + ": " + mealName + " (no permitido)");
                    log.info("[MANUAL] omitido (no permitido): '{}' comida='{}'", titular.getFullName(), mealName);
                    continue;
                }
                // No permitir volver a registrar un plato ya consumido hoy (huella o manual).
                if (consumedToday.contains(mealName)) {
                    skipped.add(titular.getFullName() + ": " + mealName + " (ya registrada)");
                    log.info("[MANUAL] omitido (duplicado): '{}' comida='{}'", titular.getFullName(), mealName);
                    continue;
                }
                Consumption consumption = Consumption.builder()
                        .employee(titular)
                        .restaurant(restaurant)
                        .proxyEmployee(proxyEmp)
                        .proxyExternalPerson(proxyExternal)
                        .consumedAt(now)
                        .businessDate(businessDate)
                        .observation(observation)
                        .method(Method.MANUAL)
                        .offline(false)
                        .syncStatus(SyncStatus.SYNCED)
                        .mealName(mealName)
                        .clientUuid(UUID.randomUUID())
                        .build();
                consumptionRepository.save(consumption);
                consumedToday.add(mealName);
                created++;
                lastMealName = mealName;
                log.info("[MANUAL] ✓ '{}' retira de '{}' (comida='{}')",
                        proxyName, titular.getFullName(), mealName);
            }
        }

        if (created == 0) {
            String msg = skipped.isEmpty()
                    ? "No se creó ningún consumo (titulares inválidos)"
                    : "No se registró nada. " + String.join("; ", skipped);
            String status = skipped.stream().anyMatch(s -> s.contains("ya registrada")) ? "DUPLICATE" : "ERROR";
            return new ManualScanResponse(status, msg, proxyName, null, 0);
        }
        String msg = created + " registro(s) creado(s) por " + proxyName;
        if (!skipped.isEmpty()) msg += ". Omitidos: " + String.join("; ", skipped);
        return new ManualScanResponse("SUCCESS", msg, proxyName, lastMealName, created);
    }

    /**
     * Disponibilidad de comidas del empleado para el registro manual de HOY: qué platos
     * tiene permitidos y cuáles aún no consumió. Lo usan las UIs (web y móvil) para mostrar
     * y pre-seleccionar solo las comidas registrables.
     */
    @Transactional(readOnly = true)
    public MealAvailabilityResponse mealAvailability(Long employeeId) {
        Employee e = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "Empleado no encontrado."));
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<String> consumed = consumptionRepository
                .findMealNamesByEmployeeIdAndBusinessDate(employeeId, today);
        boolean hadAlmuerzo = consumed.contains("Almuerzo");
        boolean hadMerienda = consumed.contains("Merienda");
        List<String> available = new ArrayList<>();
        if (e.isAllowsLunch() && !hadAlmuerzo) available.add("BREAKFAST");
        if (e.effectiveSnack() && !hadMerienda) available.add("LUNCH");
        return new MealAvailabilityResponse(employeeId, e.isAllowsLunch(), e.effectiveSnack(),
                hadAlmuerzo, hadMerienda, available);
    }

    /**
     * Traduce el código de tipo de comida al nombre usado en los reportes.
     * Solo existen dos platos: Almuerzo (BREAKFAST, 1er plato) y Merienda (LUNCH, 2º plato).
     * Los códigos se conservan por compatibilidad con los clientes existentes;
     * SNACK se acepta por compatibilidad con clientes antiguos y equivale a Merienda.
     */
    private static String mealNameForCode(String mealTypeCode) {
        if ("LUNCH".equalsIgnoreCase(mealTypeCode) || "SNACK".equalsIgnoreCase(mealTypeCode)) {
            return "Merienda";
        }
        return "Almuerzo";
    }

    /**
     * Registra un consumo para una persona externa (no empleada). Crea (o reutiliza)
     * una fila de {@code persona_externa} — tabla SEPARADA de empleado — para que el
     * consumo quede referenciado y aparezca en el feed del kiosk y en reportes, sin
     * que la persona externa aparezca jamás en la gestión/exportación de empleados.
     * Si la cédula pertenece a un empleado, se rechaza: ese consumo debe registrarse
     * por huella o por "retira por otro".
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
            return new ManualScanResponse("ERROR", "Restaurant no encontrado", req.fullName(), null, 0);
        }

        // El horario se valida ANTES de crear la persona externa: si se validara
        // después, un intento fuera de horario dejaría una persona externa huérfana
        // (sin consumo asociado) en la base.
        Schedule sch = scheduleRepository.findFirstByOrderByIdAsc().orElse(null);
        if (sch == null || !sch.isActive() || !sch.contains(LocalTime.now(BUSINESS_ZONE))) {
            return new ManualScanResponse("OUT_OF_SCHEDULE",
                    "Fuera del horario permitido para registros externos", req.fullName(), null, 0);
        }

        // Un empleado NO se registra como externo: empleados y personas externas
        // son mundos separados (el consumo del empleado cuenta para su control
        // diario, el del externo no).
        if (employeeRepository.existsByIdentityCardAndDeletedFalse(identityCard)) {
            log.info("[EXTERNAL] rechazado: cedula='{}' pertenece a un empleado", identityCard);
            return new ManualScanResponse("IS_EMPLOYEE",
                    "La cédula ingresada pertenece a un empleado. Registre su consumo por huella o con 'Retira por otro'.",
                    req.fullName(), null, 0);
        }

        ExternalPerson person = externalPersonRepository.findByIdentityCard(identityCard).orElse(null);
        if (person == null) {
            person = externalPersonRepository.save(ExternalPerson.builder()
                    .identityCard(identityCard)
                    .fullName(req.fullName())
                    .build());
            log.info("[EXTERNAL] Persona externa creada: id={}, cedula='{}', nombre='{}'",
                    person.getId(), person.getIdentityCard(), person.getFullName());
        }

        // Quien retira el plato a nombre del externo (opcional): empleado interno
        // ACTIVE o persona externa ya registrada — como mucho uno de los dos. No
        // puede ser la misma persona que el titular (se compara por cédula:
        // empleado y persona externa son tablas distintas y sus ids no son comparables).
        if (req.proxyEmployeeId() != null && req.proxyExternalPersonId() != null) {
            return new ManualScanResponse("ERROR",
                    "Solo puede indicar una persona que retira (empleado o externa, no ambas).",
                    req.fullName(), null, 0);
        }
        Employee proxy = null;
        ExternalPerson proxyExt = null;
        if (req.proxyEmployeeId() != null) {
            proxy = employeeRepository.findById(req.proxyEmployeeId()).orElse(null);
            if (proxy == null || proxy.isDeleted()) {
                return new ManualScanResponse("NOT_FOUND",
                        "Empleado que retira no encontrado", req.fullName(), null, 0);
            }
            if (proxy.getStatus() != EmployeeStatus.ACTIVE) {
                return new ManualScanResponse("ERROR",
                        "El empleado que retira está inactivo y no puede realizar registros manuales.",
                        req.fullName(), null, 0);
            }
            if (proxy.getIdentityCard().equals(identityCard)) {
                return new ManualScanResponse("ERROR",
                        "El empleado que retira no puede ser la misma persona externa.",
                        req.fullName(), null, 0);
            }
        }
        if (req.proxyExternalPersonId() != null) {
            proxyExt = externalPersonRepository.findById(req.proxyExternalPersonId()).orElse(null);
            if (proxyExt == null) {
                return new ManualScanResponse("NOT_FOUND",
                        "La persona externa que retira no está registrada.", req.fullName(), null, 0);
            }
            if (proxyExt.getIdentityCard().equals(identityCard)) {
                return new ManualScanResponse("ERROR",
                        "La persona externa que retira no puede ser la misma que el titular.",
                        req.fullName(), null, 0);
            }
        }
        String proxyName = proxy != null ? proxy.getFullName()
                : (proxyExt != null ? proxyExt.getFullName() : null);

        LocalDate businessDate = LocalDate.now(BUSINESS_ZONE);
        String mealName = mealNameForCode(req.mealTypeCode());
        // No permitir registrar dos veces el mismo plato el mismo día para esta persona
        // externa (misma cédula/pasaporte). En un externo recién creado la lista está vacía.
        List<String> consumedToday = consumptionRepository
                .findMealNamesByExternalPersonIdAndBusinessDate(person.getId(), businessDate);
        if (consumedToday.contains(mealName)) {
            log.info("[EXTERNAL] omitido (duplicado): '{}' comida='{}'", person.getFullName(), mealName);
            return new ManualScanResponse("DUPLICATE",
                    mealName + " ya fue registrado hoy para " + person.getFullName(),
                    person.getFullName(), mealName, 0);
        }

        String userObservation = blankToNull(req.observation());
        String observation;
        if (proxyName != null) {
            observation = proxyName + " retira de " + person.getFullName();
            if (userObservation != null) observation += " — " + userObservation;
        } else {
            observation = userObservation;
        }

        Consumption consumption = Consumption.builder()
                .externalPerson(person)
                .restaurant(restaurant)
                .proxyEmployee(proxy)
                .proxyExternalPerson(proxyExt)
                .consumedAt(OffsetDateTime.now(BUSINESS_ZONE))
                .businessDate(businessDate)
                .observation(observation)
                .method(Method.EXTERNAL)
                .offline(false)
                .syncStatus(SyncStatus.SYNCED)
                .mealName(mealName)
                .clientUuid(UUID.randomUUID())
                .build();
        consumptionRepository.save(consumption);

        log.info("[EXTERNAL] ✓ SUCCESS → nombre='{}', restaurant='{}', comida='{}', proxy='{}'",
                person.getFullName(), restaurant.getName(), mealName, proxyName);
        String message = proxyName != null
                ? "REGISTRO EXITOSO (retirado por " + proxyName + ")"
                : "REGISTRO EXITOSO";
        return new ManualScanResponse("SUCCESS", message,
                person.getFullName(), mealName, 1);
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private byte[] decode(String b64) {
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_TEMPLATE", "Plantilla biométrica inválida.");
        }
    }
}
