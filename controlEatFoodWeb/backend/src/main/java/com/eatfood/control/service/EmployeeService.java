package com.eatfood.control.service;

import com.eatfood.control.domain.Employee;
import com.eatfood.control.domain.EmployeeStatus;
import com.eatfood.control.dto.EmployeeDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.exception.NotFoundException;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.FingerprintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    /** Alfabeto Base32 sin caracteres ambiguos (sin O, 0, I, 1, L). */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmployeeRepository employeeRepository;
    private final FingerprintRepository fingerprintRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<EmployeeResponse> search(String term, Pageable pageable) {
        return employeeRepository.search(term, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public EmployeeResponse get(Long id) {
        return toResponse(find(id));
    }

    /** Todos los empleados no eliminados (sin paginar) para exportación. */
    @Transactional(readOnly = true)
    public List<EmployeeResponse> exportAll() {
        return employeeRepository.findByDeletedFalseOrderByFullName().stream()
                .map(this::toResponse).toList();
    }

    @Transactional
    public EmployeeResponse create(EmployeeRequest req) {
        if (employeeRepository.existsByIdentityCardAndDeletedFalse(req.identityCard())) {
            throw new BusinessException("DUPLICATE_CARD", "Ya existe un empleado con esa cédula.");
        }
        Employee e = new Employee();
        apply(e, req);
        e.setPublicCode(generatePublicCode());
        e = employeeRepository.save(e);
        auditService.record("Employee", String.valueOf(e.getId()), "CREATE", null, e.getFullName());
        return toResponse(e);
    }

    @Transactional
    public EmployeeResponse update(Long id, EmployeeRequest req) {
        Employee e = find(id);
        String before = snapshot(e);
        apply(e, req);
        e = employeeRepository.save(e);
        auditService.record("Employee", String.valueOf(e.getId()), "UPDATE", before, snapshot(e));
        return toResponse(e);
    }



    private Employee find(Long id) {
        Employee e = employeeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Empleado no encontrado: " + id));
        if (e.isDeleted()) throw new NotFoundException("Empleado no encontrado: " + id);
        return e;
    }

    private void apply(Employee e, EmployeeRequest req) {
        e.setIdentityCard(req.identityCard());
        e.setFullName(req.fullName());
        e.setObservation(blankToNull(req.observation()));
        if (req.status() != null) {
            try {
                e.setStatus(EmployeeStatus.valueOf(req.status()));
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("INVALID_STATUS", "Estado inválido: " + req.status());
            }
        }
        if (req.allowsLunch() != null) e.setAllowsLunch(req.allowsLunch());
        if (req.allowsSnack() != null) e.setAllowsSnack(req.allowsSnack());
    }

    /** Genera un código público único EMP-XXXXXX reintentando ante colisión. */
    private String generatePublicCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder("EMP-");
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            String code = sb.toString();
            if (!employeeRepository.existsByPublicCode(code)) return code;
        }
        throw new BusinessException("CODE_GENERATION", "No se pudo generar un código único de empleado.");
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private String snapshot(Employee e) {
        return "%s|%s|almuerzo=%s|merienda=%s|estado=%s".formatted(
                e.getIdentityCard(), e.getFullName(),
                e.isAllowsLunch(), e.isAllowsSnack(), e.getStatus());
    }

    private EmployeeResponse toResponse(Employee e) {
        long fpCount = fingerprintRepository.countByEmployeeIdAndActiveTrue(e.getId());
        return new EmployeeResponse(
                e.getId(),
                e.getIdentityCard(),
                e.getFullName(),
                e.getPublicCode(),
                e.getObservation(),
                e.getStatus().name(),
                e.isAllowsLunch(),
                e.isAllowsSnack(),
                e.effectiveSnack(),
                (int) fpCount);
    }
}
