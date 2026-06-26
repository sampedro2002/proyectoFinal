package com.eatfood.control.web;

import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.service.ScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Registro Manual de Consumos")
@RestController
@RequestMapping("/api/manual-consumptions")
@RequiredArgsConstructor
public class ManualScanController {

    private final ScanService scanService;

    @Operation(summary = "Registra un consumo manualmente sin huella (solo ADMIN)")
    @PostMapping
    @RolesAllowed("ADMIN")
    public ManualScanResponse register(@Valid @RequestBody ManualScanRequest req) {
        return scanService.manualScan(req);
    }
}
