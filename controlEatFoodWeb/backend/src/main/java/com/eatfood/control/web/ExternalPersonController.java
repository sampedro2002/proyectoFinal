package com.eatfood.control.web;

import com.eatfood.control.dto.ExternalPersonDtos.*;
import com.eatfood.control.service.ExternalPersonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@PreAuthorize("hasAnyRole('ADMIN', 'RECURSOS_HUMANOS')")

@Tag(name = "Personas Externas")
@RestController
@RequestMapping("/api/external-persons")
@RequiredArgsConstructor
public class ExternalPersonController {

    private final ExternalPersonService externalPersonService;

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
}
