package com.eatfood.control.service;

import com.eatfood.control.biometric.BiometricMatcher;
import com.eatfood.control.domain.Employee;
import com.eatfood.control.domain.Fingerprint;
import com.eatfood.control.dto.FingerprintDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.exception.NotFoundException;
import com.eatfood.control.repository.AppUserRepository;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.FingerprintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FingerprintService {

    private static final int MAX_FINGERPRINTS = 3;

    private final FingerprintRepository fingerprintRepository;
    private final EmployeeRepository employeeRepository;
    private final AppUserRepository userRepository;
    private final BiometricMatcher matcher;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<FingerprintResponse> listByEmployee(Long employeeId) {
        return fingerprintRepository.findByEmployeeIdAndActiveTrue(employeeId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional
    public FingerprintResponse enroll(EnrollRequest req) {
        Employee employee = employeeRepository.findById(req.employeeId())
                .orElseThrow(() -> new NotFoundException("Empleado no encontrado: " + req.employeeId()));

        // Abortar si el motor biométrico no está listo: sin él no podemos garantizar unicidad.
        if (!matcher.isReady()) {
            throw new BusinessException("ENGINE_NOT_READY",
                    "No se puede registrar la huella porque el motor biométrico no está disponible " +
                    "para validar duplicados. Verifique que el lector ZK9500 esté conectado.");
        }

        byte[] template;
        try {
            template = Base64.getDecoder().decode(req.templateB64());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("INVALID_TEMPLATE", "La plantilla biométrica no es válida (base64).");
        }

        log.info("enroll: empleado={}, dedo={}, template={} bytes (base64={} chars).",
                req.employeeId(), req.fingerIndex(), template.length, req.templateB64().length());

        // Unicidad biométrica global: la plantilla no debe pertenecer a OTRO empleado distinto.
        // Si pertenece al mismo empleado, se permite actualizar (re-enrolamiento).
        Optional<BiometricMatcher.MatchResult> duplicate = matcher.identify(template);
        if (duplicate.isPresent()) {
            long matchedEmployee = duplicate.get().employeeId();
            log.info("enroll: huella coincide con empleado={} (score={}).", matchedEmployee, duplicate.get().score());
            if (matchedEmployee != employee.getId()) {
                throw new BusinessException("DUPLICATE_BIOMETRIC",
                        "Esta huella ya está registrada para otro empleado. " +
                        "Cada huella debe ser única en todo el sistema.");
            }
        }

        // Si existe una huella (activa o inactiva) para el mismo dedo, reutilizarla en
        // lugar de insertar una nueva fila. Esto evita violar la UNIQUE
        // (employee_id, finger_index) del esquema cuando el usuario re-registra un dedo
        // que ya tenía una huella (activa o previamente borrada).
        Optional<Fingerprint> existing = fingerprintRepository
                .findByEmployeeIdAndFingerIndexAndActiveFalse(employee.getId(), req.fingerIndex());
        if (existing.isEmpty()) {
            existing = fingerprintRepository
                    .findByEmployeeIdAndFingerIndexAndActiveTrue(employee.getId(), req.fingerIndex());
        }

        Fingerprint fp;
        if (existing.isPresent()) {
            log.info("enroll: reactivando/actualizando huella id={} para empleado={}, dedo={}.",
                    existing.get().getId(), employee.getId(), req.fingerIndex());
            fp = existing.get();
            fp.setTemplate(template);
            fp.setEnrolledBy(currentUserId());
            fp.setEnrolledAt(OffsetDateTime.now());
            fp.setActive(true);
        } else {
            long count = fingerprintRepository.countByEmployeeIdAndActiveTrue(employee.getId());
            if (count >= MAX_FINGERPRINTS) {
                throw new BusinessException("MAX_FINGERPRINTS",
                        "El empleado ya tiene el máximo de " + MAX_FINGERPRINTS + " huellas registradas.");
            }
            fp = Fingerprint.builder()
                    .employee(employee)
                    .fingerIndex(req.fingerIndex())
                    .template(template)
                    .enrolledBy(currentUserId())
                    .active(true)
                    .build();
        }
        fp = fingerprintRepository.save(fp);
        log.info("enroll: huella id={} guardada en BD para empleado={}, dedo={}.",
                fp.getId(), employee.getId(), req.fingerIndex());

        matcher.enroll(fp.getId(), employee.getId(), template);
        auditService.record("Fingerprint", String.valueOf(fp.getId()), "ENROLL",
                null, "empleado=" + employee.getId() + ", dedo=" + req.fingerIndex());
        return toResponse(fp);
    }

    @Transactional
    public void delete(Long fingerprintId) {
        Fingerprint fp = fingerprintRepository.findById(fingerprintId)
                .orElseThrow(() -> new NotFoundException("Huella no encontrada: " + fingerprintId));
        fp.setActive(false);
        fingerprintRepository.save(fp);
        matcher.remove(fp.getId());
        auditService.record("Fingerprint", String.valueOf(fingerprintId), "DELETE", null, null);
    }

    @Transactional
    public String deleteAll() {
        long count = fingerprintRepository.count();
        // Auditar ANTES de borrar: deja constancia de quién ejecutó la operación
        // destructiva y de cuántas huellas se eliminaron (inmutable en el log).
        auditService.record("Fingerprint", "ALL", "CLEAN_ALL", count + " huellas", "0");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        log.warn("[FP] CLEAN_ALL solicitado por '{}': se eliminarán {} huellas.",
                auth != null ? auth.getName() : "system", count);
        fingerprintRepository.deleteAll();
        matcher.rebuildIndex();
        return "Se eliminaron " + count + " huellas de la base de datos.";
    }

    /** Fuerza la reconstrucción del índice biométrico en memoria desde la BD. */
    public String forceRebuildIndex() {
        matcher.rebuildIndex();
        return "Índice biométrico reconstruido. Plantillas cargadas: " + matcher.indexSize();
    }

    /** Devuelve el estado actual del motor biométrico y su índice en memoria. */
    public Map<String, Object> biometricStatus() {
        long activeInDb = fingerprintRepository.findByActiveTrue().size();
        return Map.of(
                "engineReady", matcher.isReady(),
                "indexSize", matcher.indexSize(),
                "activeInDb", activeInDb,
                "indexMatchesDb", matcher.indexSize() == activeInDb
        );
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        return userRepository.findByUsername(auth.getName()).map(u -> u.getId()).orElse(null);
    }

    private FingerprintResponse toResponse(Fingerprint fp) {
        return new FingerprintResponse(
                fp.getId(), fp.getEmployee().getId(), fp.getFingerIndex(),
                fp.getEnrolledBy(), fp.getEnrolledAt(), fp.isActive());
    }
}
