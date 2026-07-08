package com.eatfood.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ScanDtos {

    /** Conexión de un dispositivo de restaurant (control de máx. 2 simultáneos). */
    public record DeviceConnectRequest(
            @NotBlank String restaurantUsername,
            @NotBlank String restaurantPassword,
            @NotBlank String deviceUid,
            String deviceName) {}

    public record DeviceConnectResponse(
            Long restaurantId,
            String restaurantName,
            Long deviceId,
            String sessionToken) {}

    /** Petición de escaneo de huella desde el dispositivo de restaurant. */
    public record ScanRequest(
            @NotBlank String sessionToken,
            @NotBlank String templateB64,
            String mealTypeCode,           // opcional: si no se envía, se infiere por horario
            UUID clientUuid,               // idempotencia (offline)
            Boolean offline,
            OffsetDateTime consumedAt) {}   // hora real del consumo (para registros offline)

    /** Resultado mostrado en la pantalla del restaurant. */
    public record ScanResponse(
            String status,        // SUCCESS, NOT_FOUND, OUT_OF_SCHEDULE, DUPLICATE, NOT_ALLOWED
            String message,       // mensaje a mostrar
            String employeeName,
            String mealName,
            Integer plates,
            OffsetDateTime time) {}

    /** Lote de registros offline para sincronización. */
    public record SyncBatchRequest(
            @NotBlank String sessionToken,
            List<ScanRequest> records) {}

    public record SyncBatchResponse(
            int received,
            int applied,
            int duplicates,
            int rejected,
            List<SyncItemResult> results) {}

    public record SyncItemResult(UUID clientUuid, String status, String message) {}

    /** Entrada del feed de consumos del día para el Kiosk. */
    public record TodayEntry(
            String employeeName,
            String mealName,
            String time) {}

    /** Registro manual de consumo (sin huella) — solo ADMIN. */
    public record ManualScanRequest(
            @NotNull Long employeeId,
            @NotBlank String mealTypeCode,
            @NotNull Long restaurantId,
            String observation) {}

    public record ManualScanResponse(
            String status,
            String message,
            String employeeName,
            String mealName) {}

    /** Registro de consumo para una persona externa (no empleada). */
    public record ExternalScanRequest(
            @NotBlank String identityCard,
            @NotBlank String fullName,
            @NotBlank String mealTypeCode,
            @NotNull Long restaurantId,
            String observation) {}
}
