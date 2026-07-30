package com.eatfood.control.service;

import com.eatfood.control.domain.ExternalPerson;
import com.eatfood.control.dto.ExternalPersonDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.exception.NotFoundException;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.ExternalPersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExternalPersonService {

    private final ExternalPersonRepository externalPersonRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<ExternalPersonResponse> search(String term, Pageable pageable) {
        return externalPersonRepository.search(term, pageable).map(this::toResponse);
    }

    @Transactional
    public ExternalPersonResponse update(Long id, ExternalPersonRequest req) {
        ExternalPerson p = externalPersonRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Persona externa no encontrada: " + id));

        String identityCard = normalizedCard(req.identityCard(), Boolean.TRUE.equals(req.isPassport()));
        if (externalPersonRepository.existsByIdentityCardAndIdNot(identityCard, id)) {
            throw new BusinessException("DUPLICATE_CARD", "Ya existe otra persona externa con esa cédula.");
        }
        // Empleados y personas externas son mundos separados: la cédula no puede cruzarse.
        if (employeeRepository.existsByIdentityCardAndDeletedFalse(identityCard)) {
            throw new BusinessException("IS_EMPLOYEE", "La cédula pertenece a un empleado registrado.");
        }

        String before = snapshot(p);
        p.setIdentityCard(identityCard);
        p.setFullName(req.fullName());
        p.setObservation(blankToNull(req.observation()));
        p = externalPersonRepository.save(p);
        auditService.record("ExternalPerson", String.valueOf(id), "UPDATE", before, snapshot(p));
        return toResponse(p);
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
        // Un pasaporte no puede coincidir con el formato de una cédula ecuatoriana válida:
        // evita que alguien marque "Pasaporte" para saltarse la validación de cédula.
        if (isPassport && com.eatfood.control.util.CedulaValidator.isValid(card)) {
            throw new BusinessException("PASSPORT_LOOKS_LIKE_CEDULA",
                    "Ese número corresponde a una cédula ecuatoriana válida; no puede registrarse como pasaporte.");
        }
        return card;
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private String snapshot(ExternalPerson p) {
        return "%s|%s|obs=%s".formatted(p.getIdentityCard(), p.getFullName(), p.getObservation());
    }

    private ExternalPersonResponse toResponse(ExternalPerson p) {
        return new ExternalPersonResponse(p.getId(), p.getIdentityCard(), p.getFullName(), p.getObservation());
    }
}
