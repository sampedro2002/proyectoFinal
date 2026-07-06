package com.eatfood.control.web;

import com.eatfood.control.dto.ReportDtos.*;
import com.eatfood.control.service.ExportService;
import com.eatfood.control.service.ReportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Reportes y estadísticas")
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ReportController {

    private final ReportService reportService;
    private final ExportService exportService;

    @GetMapping("/consumptions")
    public List<ConsumptionRow> consumptions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long cateringId,
            @RequestParam(required = false) Long mealTypeId,
            @RequestParam(required = false) Long employeeId) {
        return reportService.consumptions(from, to, cateringId, mealTypeId, employeeId);
    }

    @GetMapping("/dashboard")
    public DashboardStats dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.dashboard(date);
    }

    @GetMapping("/not-consumed")
    public List<EmployeeNotConsumed> notConsumed(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.notConsumed(date);
    }

    @GetMapping("/trend")
    public List<TrendPoint> trend(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.trend(from, to);
    }

    // ---- Exportaciones ----
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam String format, // csv | excel | pdf
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long cateringId,
            @RequestParam(required = false) Long mealTypeId,
            @RequestParam(required = false) Long employeeId) {

        List<ConsumptionRow> rows = reportService.consumptions(from, to, cateringId, mealTypeId, employeeId);
        byte[] body;
        String filename;
        MediaType mediaType;

        switch (format.toLowerCase()) {
            case "excel" -> {
                body = exportService.toExcel(rows);
                filename = "consumos.xlsx";
                mediaType = MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            }
            case "pdf" -> {
                body = exportService.toPdf(rows, "Consumos " + from + " a " + to);
                filename = "consumos.pdf";
                mediaType = MediaType.APPLICATION_PDF;
            }
            default -> {
                body = exportService.toCsv(rows);
                filename = "consumos.csv";
                mediaType = MediaType.parseMediaType("text/csv");
            }
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(body);
    }
}
