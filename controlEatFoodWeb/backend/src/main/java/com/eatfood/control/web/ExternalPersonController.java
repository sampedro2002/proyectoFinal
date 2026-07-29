package com.eatfood.control.web;

import com.eatfood.control.domain.Employee;
import com.eatfood.control.domain.ExternalPerson;
import com.eatfood.control.dto.ExternalPersonDtos.*;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.ExternalPersonRepository;
import com.eatfood.control.service.ExternalPersonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@PreAuthorize("hasAnyRole('ADMIN', 'RECURSOS_HUMANOS')")

@Tag(name = "Personas Externas")
@RestController
@RequestMapping("/api/external-persons")
@RequiredArgsConstructor
public class ExternalPersonController {

    private final ExternalPersonService externalPersonService;
    private final EmployeeRepository employeeRepository;
    private final ExternalPersonRepository externalPersonRepository;

    @Operation(summary = "Lista personas externas con buscador por nombre/cédula (solo ADMIN/RRHH)")
    @GetMapping
    public Page<ExternalPersonResponse> list(@RequestParam(required = false) String term, Pageable pageable) {
        return externalPersonService.search(term, pageable);
    }

    @Operation(summary = "Actualiza nombre, cédula u observación de una persona externa (solo ADMIN/RRHH)")
    @PutMapping("/{id}")
    public ExternalPersonResponse update(@PathVariable Long id, @Valid @RequestBody ExternalPersonRequest req) {
        return externalPersonService.update(id, req);
    }

    /**
     * Busca por cédula exacta en empleados y personas externas.
     * Devuelve { found: true, fullName, source: "EMPLOYEE"|"EXTERNAL" } si existe,
     * o { found: false } si no.
     */
    @Operation(summary = "Busca si una cédula ya está registrada (empleado o persona externa)")
    @GetMapping("/lookup")
    public Map<String, Object> lookup(@RequestParam String identityCard) {
        // Primero buscar en empleados (prioridad)
        Optional<Employee> emp = employeeRepository.findByIdentityCardAndDeletedFalse(identityCard.trim());
        if (emp.isPresent()) {
            return Map.of("found", true, "fullName", emp.get().getFullName(), "source", "EMPLOYEE");
        }
        // Luego en personas externas
        Optional<ExternalPerson> ext = externalPersonRepository.findByIdentityCard(identityCard.trim());
        if (ext.isPresent()) {
            return Map.of("found", true, "fullName", ext.get().getFullName(), "source", "EXTERNAL");
        }
        return Map.of("found", false);
    }
}
