package com.eatfood.control.mobile.data.model

/**
 * Modelos de transferencia (DTO) que reflejan exactamente los `record` del backend
 * Spring Boot (paquete com.eatfood.control.dto). Los nombres de campo coinciden con
 * el JSON que produce/consume la API; Gson hace el mapeo directo.
 */

// ── Auth ─────────────────────────────────────────────────────────────────────
data class LoginRequest(val username: String, val password: String)
data class RefreshRequest(val refreshToken: String)
data class LogoutRequest(val refreshToken: String)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val username: String?,
    val fullName: String?,
    val roles: List<String>?,
    val restaurantId: Long?
)

// ── Empleados ────────────────────────────────────────────────────────────────
data class EmployeeRequest(
    val identityCard: String,
    val fullName: String,
    val observation: String?,
    val isPassport: Boolean?,
    val status: String?,
    val allowsLunch: Boolean?,
    val allowsSnack: Boolean?
)

data class EmployeeResponse(
    val id: Long,
    val identityCard: String,
    val fullName: String,
    val publicCode: String?,
    val observation: String?,
    val status: String?,
    val allowsLunch: Boolean,
    val allowsSnack: Boolean,
    val effectiveSnack: Boolean,
    val fingerprintCount: Int
)

/** Respuesta paginada de Spring Data (sólo los campos que usamos). */
data class Page<T>(
    val content: List<T>?,
    val totalElements: Long?,
    val totalPages: Int?,
    val number: Int?
)

// ── Huellas ──────────────────────────────────────────────────────────────────
data class EnrollRequest(val employeeId: Long, val fingerIndex: Int, val templateB64: String)

data class FingerprintResponse(
    val id: Long,
    val employeeId: Long,
    val fingerIndex: Int,
    val enrolledBy: Long?,
    val enrolledAt: String?,
    val active: Boolean
)

// ── Catálogos ────────────────────────────────────────────────────────────────
data class RestaurantRequest(
    val name: String, val location: String?, val representative: String?,
    val active: Boolean?, val maxDevices: Int?
)
data class RestaurantResponse(
    val id: Long, val name: String, val location: String?, val representative: String?,
    val active: Boolean, val maxDevices: Int, val connectedDevices: Long
)

// ── Usuarios (CRUD de la pantalla Usuarios, igual que la web) ────────────────
data class UserRequest(
    val username: String,
    val fullName: String,
    val email: String?,
    // Requerida al crear; null = no cambiar al actualizar.
    val password: String?,
    val enabled: Boolean?,
    // Nombres de rol: ADMIN / CATERING.
    val roles: List<String>?,
    val restaurantId: Long?
)

data class UserResponse(
    val id: Long,
    val username: String,
    val fullName: String,
    val email: String?,
    val enabled: Boolean,
    val roles: List<String>?,
    val restaurantId: Long?,
    val restaurantName: String?
)

data class RoleResponse(val id: Long?, val name: String?, val description: String?)
data class EnabledRequest(val enabled: Boolean)
data class PasswordResetRequest(val password: String)

/** Request para actualizar el horario general único (igual que la web). */
data class GeneralScheduleRequest(val startTime: String, val endTime: String, val active: Boolean?)
data class ScheduleResponse(
    val id: Long, val startTime: String?, val endTime: String?, val active: Boolean
)

// ── Escaneo / restaurant ───────────────────────────────────────────────────────
data class DeviceConnectRequest(
    val restaurantUsername: String,
    val restaurantPassword: String,
    val deviceUid: String,
    val deviceName: String?
)

data class DeviceConnectResponse(
    val restaurantId: Long?,
    val restaurantName: String?,
    val deviceId: Long?,
    val sessionToken: String
)

data class ScanRequest(
    val sessionToken: String? = null,
    val templateB64: String? = null,
    val mealTypeCode: String? = null,
    val clientUuid: String? = null,
    val offline: Boolean? = null,
    val consumedAt: String? = null,
    val manualIdentityCard: String? = null,
    val manualFullName: String? = null
)

data class ScanResponse(
    val status: String,
    val message: String?,
    val employeeName: String?,
    val mealName: String?,
    val plates: Int?,
    val time: String?
)

data class SyncBatchRequest(val sessionToken: String, val records: List<ScanRequest>)
data class SyncItemResult(val clientUuid: String?, val status: String?, val message: String?)
data class SyncBatchResponse(
    val received: Int, val applied: Int, val duplicates: Int, val rejected: Int,
    val results: List<SyncItemResult>?
)

/** Entrada del feed de consumos del día (GET /api/scan/today). */
data class TodayFeedEntry(
    val employeeName: String?,
    val mealName: String?,
    val time: String?
)

/** Respuesta del feed diario: incluye el nombre actualizado del restaurante. */
data class TodayFeedResponse(
    val restaurantName: String?,
    val entries: List<TodayFeedEntry>?
)

// ── Reportes ─────────────────────────────────────────────────────────────────
data class ConsumptionRow(
    val id: Long,
    val businessDate: String?,
    val consumedAt: String?,
    val employeeName: String?,
    val identityCard: String?,
    val restaurantName: String?,
    val mealName: String?,
    val observation: String?,
    val offline: Boolean
)

data class DashboardStats(
    val date: String?,
    val totalConsumptions: Long,
    val almuerzoCount: Long,
    val meriendaCount: Long,
    val expectedEmployees: Long,
    val employeesConsumed: Long,
    val employeesPending: Long,
    val consumptionPercentage: Double,
    val failedNotFound: Long,
    val failedOutOfSchedule: Long
)

data class TrendPoint(val date: String?, val records: Long)

// ── Auditoría ────────────────────────────────────────────────────────────────
data class AuditRow(
    val id: Long,
    val createdAt: String?,
    val username: String?,
    val entityName: String?,
    val entityId: String?,
    val action: String?,
    val oldValue: String?,
    val newValue: String?,
    val ipAddress: String?
)

// ── Error del backend ─────────────────────────────────────────────────────────
data class ApiError(
    val timestamp: String? = null,
    val status: Int? = null,
    val code: String? = null,
    val message: String? = null
)

data class ManualScanRequest(
    val employeeId: Long,
    val mealTypeCode: String,
    val restaurantId: Long,
    val observation: String? = null
)

data class ExternalScanRequest(
    val identityCard: String,
    val fullName: String,
    val mealTypeCode: String,
    val restaurantId: Long,
    val observation: String? = null,
    val isPassport: Boolean? = null
)

data class ManualScanResponse(
    val status: String,
    val message: String?,
    val employeeName: String?,
    val mealName: String?
)
