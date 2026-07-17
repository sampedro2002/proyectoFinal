package com.eatfood.control.web;

import com.eatfood.control.domain.Restaurant;
import com.eatfood.control.dto.ReportDtos.*;
import com.eatfood.control.repository.RestaurantRepository;
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
    private final RestaurantRepository restaurantRepository;

    @GetMapping("/consumptions")
    public List<ConsumptionRow> consumptions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long restaurantId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) List<String> method) {
        List<String> methods = (method == null || method.stream().allMatch(String::isBlank))
                ? null
                : method.stream().filter(s -> !s.isBlank()).toList();
        return reportService.consumptions(from, to, restaurantId, employeeId, methods);
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
            @RequestParam(required = false) Long restaurantId,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) List<String> method) {

        List<String> methods = (method == null || method.stream().allMatch(String::isBlank))
                ? null
                : method.stream().filter(s -> !s.isBlank()).toList();
        List<ConsumptionRow> rows = reportService.consumptions(from, to, restaurantId, employeeId, methods);
        String title = buildTitle(restaurantId, from, to);
        byte[] body;
        String filename;
        MediaType mediaType;

        switch (format.toLowerCase()) {
            case "excel" -> {
                body = exportService.toExcel(rows, title);
                filename = "consumos.xlsx";
                mediaType = MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            }
            case "pdf" -> {
                body = exportService.toPdf(rows, title);
                filename = "consumos.pdf";
                mediaType = MediaType.APPLICATION_PDF;
            }
            default -> {
                body = exportService.toCsv(rows, title);
                filename = "consumos.csv";
                mediaType = MediaType.parseMediaType("text/csv");
            }
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(body);
    }

    /** Título del reporte: nombre del restaurante y el día (o rango) del consumo. */
    private String buildTitle(Long restaurantId, LocalDate from, LocalDate to) {
        String restaurantName = restaurantId == null
                ? "Todos los restaurantes"
                : restaurantRepository.findById(restaurantId)
                        .map(Restaurant::getName)
                        .orElse("Restaurante #" + restaurantId);
        String period = from.equals(to)
                ? "Consumo del día " + from
                : "Consumo del " + from + " al " + to;
        return "Restaurante: " + restaurantName + " — " + period;
    }
}
