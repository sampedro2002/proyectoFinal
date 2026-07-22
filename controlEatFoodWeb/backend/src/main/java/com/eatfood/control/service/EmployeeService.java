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

import java.util.List;

@Service
@RequiredArgsConstructor
public class EmployeeService {

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
        String identityCard = normalizedCard(req.identityCard(), Boolean.TRUE.equals(req.isPassport()));
        if (employeeRepository.existsByIdentityCardAndDeletedFalse(identityCard)) {
            throw new BusinessException("DUPLICATE_CARD", "Ya existe un empleado con esa cédula.");
        }
        // cedula es UNIQUE global en la BD: si la cédula pertenece a un empleado
        // eliminado (soft-delete), el INSERT fallaría con un 500. Se reactiva ese registro
        // con los datos nuevos en lugar de crear una fila duplicada.
        Employee revived = employeeRepository.findByIdentityCard(identityCard).orElse(null);
        if (revived != null) {
            apply(revived, req);
            revived.setDeleted(false);
            Employee saved = employeeRepository.save(revived);
            auditService.record("Employee", String.valueOf(saved.getId()), "REACTIVATE", null, saved.getFullName());
            return toResponse(saved);
        }
        Employee e = new Employee();
        apply(e, req);
        e = employeeRepository.save(e);
        auditService.record("Employee", String.valueOf(e.getId()), "CREATE", null, e.getFullName());
        return toResponse(e);
    }

    @Transactional
    public EmployeeResponse update(Long id, EmployeeRequest req) {
        Employee e = find(id);
        String identityCard = normalizedCard(req.identityCard(), Boolean.TRUE.equals(req.isPassport()));
        if (employeeRepository.existsByIdentityCardAndIdNot(identityCard, id)) {
            throw new BusinessException("DUPLICATE_CARD", "Ya existe otro empleado con esa cédula.");
        }
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
        e.setIdentityCard(normalizedCard(req.identityCard(), Boolean.TRUE.equals(req.isPassport())));
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

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Normaliza y valida la cédula. Si es pasaporte, se omite la validación de 10 dígitos.
     */
    private static String normalizedCard(String identityCard, boolean isPassport) {
        String card = identityCard == null ? "" : identityCard.trim();
        if (!isPassport && !com.eatfood.control.util.CedulaValidator.isValid(card)) {
            throw new BusinessException("INVALID_CARD",
                    "La cédula ingresada no es una cédula ecuatoriana válida (verifique los 10 dígitos).");
        }
        return card;
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
                e.getObservation(),
                e.getStatus().name(),
                e.isAllowsLunch(),
                e.isAllowsSnack(),
                e.effectiveSnack(),
                (int) fpCount);
    }
}
