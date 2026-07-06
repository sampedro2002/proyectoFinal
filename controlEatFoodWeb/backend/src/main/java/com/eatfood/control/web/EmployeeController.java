package com.eatfood.control.web;

import com.eatfood.control.dto.EmployeeDtos.*;
import com.eatfood.control.service.EmployeeService;
import com.eatfood.control.service.ExportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Empleados")
@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;
    private final ExportService exportService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Page<EmployeeResponse> list(@RequestParam(required = false) String term, Pageable pageable) {
        return employeeService.search(term, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EmployeeResponse get(@PathVariable Long id) {
        return employeeService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public EmployeeResponse create(@Valid @RequestBody EmployeeRequest req) {
        return employeeService.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EmployeeResponse update(@PathVariable Long id, @Valid @RequestBody EmployeeRequest req) {
        return employeeService.update(id, req);
    }

    // ---- Exportación de la base de empleados ----
    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "excel") String format) {
        List<EmployeeResponse> rows = employeeService.exportAll();
        byte[] body;
        String filename;
        MediaType mediaType;

        if ("csv".equalsIgnoreCase(format)) {
            body = exportService.employeesToCsv(rows);
            filename = "empleados.csv";
            mediaType = MediaType.parseMediaType("text/csv");
        } else {
            body = exportService.employeesToExcel(rows);
            filename = "empleados.xlsx";
            mediaType = MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(body);
    }
}
