package com.eatfood.control.web;

import com.eatfood.control.dto.EmployeeDtos.*;
import com.eatfood.control.service.EmployeeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Empleados")
@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public Page<EmployeeResponse> list(@RequestParam(required = false) String term, Pageable pageable) {
        return employeeService.search(term, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
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


}
