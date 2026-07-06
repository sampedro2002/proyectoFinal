package com.eatfood.control.web;

import com.eatfood.control.dto.ScanDtos.*;
import com.eatfood.control.service.DeviceService;
import com.eatfood.control.service.ExportService;
import com.eatfood.control.service.ScanService;
import com.eatfood.control.service.SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final ExportService exportService;

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

    @Operation(summary = "Exporta el reporte diario del Kiosk con conteo de platos")
    @GetMapping("/export-today")
    public ResponseEntity<byte[]> exportToday(
            @RequestParam String sessionToken,
            @RequestParam(defaultValue = "pdf") String format) {

        ScanService.KioskReport report = scanService.todayReport(sessionToken);
        byte[] body;
        String filename;
        MediaType mediaType;

        switch (format.toLowerCase()) {
            case "excel" -> {
                body = exportService.kioskDailyExcel(
                        report.cateringName(), report.date(), report.rows(), report.plateCounts());
                filename = "reporte-diario-" + report.date() + ".xlsx";
                mediaType = MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            }
            case "csv" -> {
                body = exportService.kioskDailyCsv(
                        report.cateringName(), report.date(), report.rows(), report.plateCounts());
                filename = "reporte-diario-" + report.date() + ".csv";
                mediaType = MediaType.parseMediaType("text/csv");
            }
            default -> {
                body = exportService.kioskDailyPdf(
                        report.cateringName(), report.date(), report.rows(), report.plateCounts());
                filename = "reporte-diario-" + report.date() + ".pdf";
                mediaType = MediaType.APPLICATION_PDF;
            }
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(body);
    }
}
