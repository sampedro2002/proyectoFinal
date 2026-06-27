package com.eatfood.control.web;

import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.service.ScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Registro Manual de Consumos")
@RestController
@RequestMapping("/api/manual-consumptions")
@RequiredArgsConstructor
public class ManualScanController {

    private final ScanService scanService;

    @Operation(summary = "Registra un consumo manualmente sin huella (solo ADMIN)")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ManualScanResponse register(@Valid @RequestBody ManualScanRequest req) {
        return scanService.manualScan(req);
    }

    @Operation(summary = "Registra un consumo para una persona externa (no empleada, solo ADMIN)")
    @PostMapping("/external")
    @PreAuthorize("hasRole('ADMIN')")
    public ManualScanResponse registerExternal(@Valid @RequestBody ExternalScanRequest req) {
        return scanService.registerExternal(req);
    }
}
