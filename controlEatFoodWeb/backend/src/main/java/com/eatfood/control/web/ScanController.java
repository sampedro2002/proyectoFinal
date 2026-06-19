package com.eatfood.control.web;

import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.service.DeviceService;
import com.eatfood.control.service.ScanService;
import com.eatfood.control.service.SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints usados por el dispositivo de catering. Se autentican mediante el
 * token de sesión de dispositivo (no JWT de usuario), por eso están bajo /api/scan
 * (permitAll en SecurityConfig). El token se valida en cada operación.
 */
@Tag(name = "Catering / Escaneo")
@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
public class ScanController {

    private final ScanService scanService;
    private final SyncService syncService;
    private final DeviceService deviceService;

    @Operation(summary = "Conecta un dispositivo de catering (máx. 2 simultáneos)")
    @PostMapping("/connect")
    public DeviceConnectResponse connect(@Valid @RequestBody DeviceConnectRequest req) {
        return deviceService.connect(req);
    }

    @Operation(summary = "Desconecta el dispositivo de catering")
    @PostMapping("/disconnect")
    public void disconnect(@RequestParam String sessionToken) {
        deviceService.disconnect(sessionToken);
    }

    @Operation(summary = "Procesa una huella y registra el consumo (online)")
    @PostMapping
    public ScanResponse scan(@Valid @RequestBody ScanRequest req) {
        return scanService.scan(req);
    }

    @Operation(summary = "Sincroniza un lote de registros generados en modo offline")
    @PostMapping("/sync")
    public SyncBatchResponse sync(@Valid @RequestBody SyncBatchRequest req) {
        return syncService.sync(req);
    }

    @Operation(summary = "Feed de consumos del día para el Kiosk (polling)")
    @GetMapping("/today")
    public List<TodayEntry> today(@RequestParam String sessionToken) {
        return scanService.todayFeed(sessionToken);
    }
}
