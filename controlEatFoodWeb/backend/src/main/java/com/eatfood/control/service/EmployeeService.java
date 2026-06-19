package com.eatfood.control.service;

import com.eatfood.control.domain.Employee;
import com.eatfood.control.domain.EmployeeStatus;
import com.eatfood.control.domain.Position;
import com.eatfood.control.dto.EmployeeDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.exception.NotFoundException;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.FingerprintRepository;
import com.eatfood.control.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final PositionRepository positionRepository;
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

    @Transactional
    public EmployeeResponse create(EmployeeRequest req) {
        if (employeeRepository.existsByIdentityCardAndDeletedFalse(req.identityCard())) {
            throw new BusinessException("DUPLICATE_CARD", "Ya existe un empleado con esa cédula.");
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
        if (req.positionId() != null) {
            Position p = positionRepository.findById(req.positionId())
                    .orElseThrow(() -> new NotFoundException("Cargo no encontrado: " + req.positionId()));
            e.setPosition(p);
        } else {
            e.setPosition(null);
        }
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
                e.getPosition() != null ? e.getPosition().getId() : null,
                e.getPosition() != null ? e.getPosition().getName() : null,
                e.getStatus().name(),
                e.isAllowsLunch(),
                e.effectiveSnack(),
                (int) fpCount);
    }
}
