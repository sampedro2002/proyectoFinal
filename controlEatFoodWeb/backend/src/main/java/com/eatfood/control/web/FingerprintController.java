package com.eatfood.control.web;

import com.eatfood.control.dto.FingerprintDtos.*;
import com.eatfood.control.service.FingerprintService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Huellas")
@RestController
@RequestMapping("/api/fingerprints")
@RequiredArgsConstructor
public class FingerprintController {

    private final FingerprintService fingerprintService;

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public List<FingerprintResponse> byEmployee(@PathVariable Long employeeId) {
        return fingerprintService.listByEmployee(employeeId);
    }

    @PostMapping("/enroll")
    @PreAuthorize("hasRole('ADMIN')")
    public FingerprintResponse enroll(@Valid @RequestBody EnrollRequest req) {
        return fingerprintService.enroll(req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        fingerprintService.delete(id);
    }

    // ENDPOINT TEMPORAL PARA LIMPIAR LA BD
    @DeleteMapping("/clean-all")
    public String cleanAll() {
        return fingerprintService.deleteAll();
    }

    /** Diagnóstico: estado actual del motor biométrico y su índice en memoria. */
    @GetMapping("/biometric-status")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> biometricStatus() {
        return fingerprintService.biometricStatus();
    }

    /** Diagnóstico: fuerza reconstrucción del índice biométrico desde la BD. */
    @PostMapping("/biometric-rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    public String biometricRebuild() {
        return fingerprintService.forceRebuildIndex();
    }
}
