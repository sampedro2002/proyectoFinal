package com.eatfood.control.web;

import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.service.ManualConsumptionService;
import com.eatfood.control.service.ScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@PreAuthorize("hasAnyRole('ADMIN', 'RECURSOS_HUMANOS')")

@Tag(name = "Registro Manual de Consumos")
@RestController
@RequestMapping("/api/manual-consumptions")
@RequiredArgsConstructor
public class ManualScanController {

    private final ScanService scanService;
    private final ManualConsumptionService manualConsumptionService;

    @Operation(summary = "Registra un consumo manualmente sin huella (solo ADMIN/RRHH)")
    @PostMapping
    public ManualScanResponse register(@Valid @RequestBody ManualScanRequest req) {
        return scanService.manualScan(req);
    }

    @Operation(summary = "Registra un consumo para una persona externa (no empleada, solo ADMIN/RRHH)")
    @PostMapping("/external")
    public ManualScanResponse registerExternal(@Valid @RequestBody ExternalScanRequest req) {
        return scanService.registerExternal(req);
    }

    @Operation(summary = "Comidas permitidas y aún no consumidas hoy por un empleado (solo ADMIN/RRHH)")
    @GetMapping("/availability/{employeeId}")
    public MealAvailabilityResponse availability(@PathVariable Long employeeId) {
        return scanService.mealAvailability(employeeId);
    }

    @Operation(summary = "Lista consumos manuales paginados (solo ADMIN/RRHH)")
    @GetMapping
    public Page<ConsumptionDetailResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long restaurantId,
            @RequestParam(required = false) Boolean cancelled,
            @PageableDefault(size = 20) Pageable pageable) {
        return manualConsumptionService.listManual(search, restaurantId, cancelled, pageable);
    }

    @Operation(summary = "Obtiene detalle de un consumo manual (solo ADMIN/RRHH)")
    @GetMapping("/{id}")
    public ConsumptionDetailResponse get(@PathVariable Long id) {
        return manualConsumptionService.getById(id);
    }

    @Operation(summary = "Actualiza un consumo manual (solo ADMIN/RRHH)")
    @PutMapping("/{id}")
    public ConsumptionDetailResponse update(@PathVariable Long id, @Valid @RequestBody UpdateManualConsumptionRequest req) {
        return manualConsumptionService.update(id, req);
    }

    @Operation(summary = "Cancela un consumo manual (no se cuenta en reportes, solo ADMIN/RRHH)")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        manualConsumptionService.cancel(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Reactivar un consumo manual cancelado (solo ADMIN/RRHH)")
    @PostMapping("/{id}/uncancel")
    public ResponseEntity<Void> uncancel(@PathVariable Long id) {
        manualConsumptionService.uncancel(id);
        return ResponseEntity.ok().build();
    }
}
