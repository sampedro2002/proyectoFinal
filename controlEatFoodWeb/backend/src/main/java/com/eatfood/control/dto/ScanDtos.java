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
            String time,
            String method,
            // Nombre de quien retira (empleado o persona externa); proxyExternal=true
            // cuando quien retira es una persona externa registrada.
            String proxyEmployeeName,
            boolean proxyExternal) {}

    /** Respuesta del feed diario: incluye el nombre actualizado del restaurante. */
    public record TodayFeedResponse(
            String restaurantName,
            List<TodayEntry> entries) {}

    /**
     * Registro manual de consumo (sin huella) — solo ADMIN.
     * Modelo "retira por otro": quien retira es un empleado
     * ({@code proxyEmployeeId}) o una persona externa ya registrada
     * ({@code proxyExternalPersonId}) — exactamente uno de los dos. Se genera
     * una fila de {@code consumption} por cada titular x codigo de comida, con
     * {@code method='MANUAL'}, el apoderado correspondiente y
     * {@code observation="X retira de Y"} autogenerada.
     */
    public record ManualScanRequest(
            Long proxyEmployeeId,
            Long proxyExternalPersonId,
            @NotNull Long restaurantId,
            @NotNull List<ManualScanItem> titulars) {}

    /** Un titular y los tipos de comida que el proxy retira por el. */
    public record ManualScanItem(
            Long employeeId,
            Long externalPersonId,
            @NotNull List<String> mealTypeCodes) {}

    public record ManualScanResponse(
            String status,
            String message,
            String employeeName,
            String mealName,
            Integer created) {}

    /**
     * Candidato a "quien retira" en el buscador unificado: empleado ACTIVO o
     * persona externa registrada. {@code type}: "EMPLOYEE" | "EXTERNAL".
     */
    public record ProxyCandidate(
            String type,
            Long id,
            String identityCard,
            String fullName) {}

    /**
     * Disponibilidad de comidas de un empleado para el registro manual de HOY.
     * Sirve para que las UIs (web y móvil) muestren/pre-seleccionen solo las comidas
     * que el empleado tiene permitidas y que aún no consumió en el día.
     * Códigos: BREAKFAST=Almuerzo (requiere allowsLunch), LUNCH=Merienda (requiere allowsSnack).
     */
    public record MealAvailabilityResponse(
            Long employeeId,
            boolean allowsLunch,
            boolean allowsSnack,
            boolean hadAlmuerzo,
            boolean hadMerienda,
            List<String> availableCodes) {}

    public record ExternalScanRequest(
            @NotBlank String identityCard,
            Boolean isPassport,
            @NotBlank String fullName,
            @NotBlank String mealTypeCode,
            @NotNull Long restaurantId,
            String observation,
            // Opcional: quien retira el plato a nombre del externo. Puede ser un
            // empleado interno (proxyEmployeeId) o una persona externa ya
            // registrada (proxyExternalPersonId) — como mucho uno de los dos.
            // Si ambos son null, el propio externo lo retira.
            Long proxyEmployeeId,
            Long proxyExternalPersonId) {}

    public record UpdateManualConsumptionRequest(
            Long proxyEmployeeId,
            // Al venir no nulo, el apoderado pasa a ser esta persona externa
            // registrada (y se limpia empleado_apoderado_id).
            Long proxyExternalPersonId,
            Long employeeId,
            Long restaurantId,
            String mealName,
            String observation) {}

    public record ConsumptionDetailResponse(
            Long id,
            // Titular: employeeId (empleado interno) XOR externalPersonId (persona
            // externa). Exactamente uno es no nulo; employeeName/identityCard ya
            // vienen resueltos del titular correspondiente.
            Long employeeId,
            Long externalPersonId,
            String employeeName,
            String identityCard,
            Long proxyEmployeeId,
            String proxyEmployeeName,
            // Quien retira cuando es persona externa (excluyente con proxyEmployee*).
            Long proxyExternalPersonId,
            String proxyExternalPersonName,
            Long restaurantId,
            String restaurantName,
            String mealName,
            String observation,
            String method,
            boolean offline,
            boolean cancelled,
            String businessDate,
            String consumedAt,
            String createdAt) {}
}
