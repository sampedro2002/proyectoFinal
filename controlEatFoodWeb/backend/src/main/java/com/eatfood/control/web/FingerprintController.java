package com.eatfood.control.web;

import com.eatfood.control.dto.FingerprintDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.service.FingerprintCaptureService;
import com.eatfood.control.service.FingerprintService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired(required = false)
    private FingerprintCaptureService fingerprintCaptureService;

    /**
     * Captura una huella desde el lector ZK9500 conectado al servidor y la registra
     * para el empleado y dedo indicados. No requiere lector local en el cliente.
     */
    @PostMapping("/enroll-from-server/{employeeId}/{fingerIndex}")
    @PreAuthorize("hasRole('ADMIN')")
    public FingerprintResponse enrollFromServer(
            @PathVariable Long employeeId,
            @PathVariable Short fingerIndex) {
        if (fingerprintCaptureService == null || !fingerprintCaptureService.isReaderReady()) {
            throw new BusinessException("READER_NOT_AVAILABLE",
                    "Lector ZK9500 no disponible en el servidor. " +
                    "Conecte el lector al servidor para usar esta función.");
        }
        String templateB64 = fingerprintCaptureService.captureForEnroll(120_000);
        return fingerprintService.enroll(new EnrollRequest(employeeId, fingerIndex, templateB64));
    }

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("hasRole('ADMIN')")
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

    /**
     * Herramienta de mantenimiento: elimina TODAS las huellas (p. ej. tras una migración
     * de esquema de cifrado). Operación destructiva e irreversible, por lo que exige una
     * confirmación explícita: {@code DELETE /api/fingerprints/clean-all?confirm=BORRAR-TODAS-LAS-HUELLAS}.
     * Sin el token exacto, la petición se rechaza para evitar borrados accidentales.
     */
    @DeleteMapping("/clean-all")
    @PreAuthorize("hasRole('ADMIN')")
    public String cleanAll(@RequestParam(name = "confirm", required = false) String confirm) {
        if (!"BORRAR-TODAS-LAS-HUELLAS".equals(confirm)) {
            throw new com.eatfood.control.exception.BusinessException(
                    "CONFIRMATION_REQUIRED",
                    "Operación destructiva: repita la petición con ?confirm=BORRAR-TODAS-LAS-HUELLAS para confirmar.");
        }
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
