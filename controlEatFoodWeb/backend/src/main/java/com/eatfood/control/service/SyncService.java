package com.eatfood.control.service;

import com.eatfood.control.dto.ScanDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Sincronización de registros generados en modo offline.
 * Reutiliza la lógica idempotente de {@link ScanService}: cada registro lleva un
 * {@code clientUuid} único que evita duplicados al reenviarse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    private final ScanService scanService;

    public SyncBatchResponse sync(SyncBatchRequest batch) {
        int applied = 0, duplicates = 0, rejected = 0;
        List<SyncItemResult> results = new ArrayList<>();

        for (ScanRequest raw : batch.records()) {
            // Reescribir con el token de sesión del lote y marca offline
            ScanRequest record = new ScanRequest(
                    batch.sessionToken(),
                    raw.templateB64(),
                    raw.mealTypeCode(),
                    raw.clientUuid(),
                    Boolean.TRUE,
                    raw.consumedAt());
            try {
                ScanResponse resp = scanService.scan(record);
                switch (resp.status()) {
                    case "SUCCESS" -> applied++;
                    case "DUPLICATE" -> duplicates++;
                    default -> rejected++;
                }
                results.add(new SyncItemResult(raw.clientUuid(), resp.status(), resp.message()));
            } catch (Exception e) {
                rejected++;
                results.add(new SyncItemResult(raw.clientUuid(), "ERROR", e.getMessage()));
                log.warn("Error sincronizando registro {}: {}", raw.clientUuid(), e.getMessage());
            }
        }

        return new SyncBatchResponse(batch.records().size(), applied, duplicates, rejected, results);
    }
}
