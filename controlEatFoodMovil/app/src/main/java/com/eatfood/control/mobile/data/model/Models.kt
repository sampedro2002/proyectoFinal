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
    val cateringId: Long?
)

// ── Empleados ────────────────────────────────────────────────────────────────
data class EmployeeRequest(
    val identityCard: String,
    val fullName: String,
    val positionId: Long?,
    val status: String?,
    val allowedPlates: Int?,
    val allowsLunch: Boolean?,
    val allowsSnack: Boolean?
)

data class EmployeeResponse(
    val id: Long,
    val identityCard: String,
    val fullName: String,
    val positionId: Long?,
    val positionName: String?,
    val status: String?,
    val allowedPlates: Int?,
    val effectivePlates: Int,
    val allowsLunch: Boolean,
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
data class PositionRequest(val name: String, val defaultPlates: Int, val allowsSnack: Boolean, val active: Boolean?)
data class PositionResponse(val id: Long, val name: String, val defaultPlates: Int, val allowsSnack: Boolean, val active: Boolean)

data class CateringRequest(val name: String, val location: String?, val active: Boolean?, val maxDevices: Int?)
data class CateringResponse(
    val id: Long, val name: String, val location: String?, val active: Boolean,
    val maxDevices: Int, val connectedDevices: Long
)

data class MealTypeResponse(val id: Long, val code: String, val name: String, val active: Boolean, val sortOrder: Int)

data class ScheduleRequest(val mealTypeId: Long, val startTime: String, val endTime: String, val active: Boolean?)
data class ScheduleResponse(
    val id: Long, val mealTypeId: Long, val mealTypeName: String?,
    val startTime: String?, val endTime: String?, val active: Boolean
)

// ── Escaneo / catering ───────────────────────────────────────────────────────
data class DeviceConnectRequest(
    val cateringUsername: String,
    val cateringPassword: String,
    val deviceUid: String,
    val deviceName: String?
)

data class DeviceConnectResponse(
    val cateringId: Long?,
    val cateringName: String?,
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

// ── Reportes ─────────────────────────────────────────────────────────────────
data class ConsumptionRow(
    val id: Long,
    val businessDate: String?,
    val consumedAt: String?,
    val employeeName: String?,
    val identityCard: String?,
    val positionName: String?,
    val cateringName: String?,
    val mealName: String?,
    val plates: Int,
    val offline: Boolean
)

data class DashboardStats(
    val date: String?,
    val totalConsumptions: Long,
    val lunchCount: Long,
    val snackCount: Long,
    val platesDelivered: Long,
    val expectedEmployees: Long,
    val employeesConsumed: Long,
    val employeesPending: Long,
    val consumptionPercentage: Double,
    val failedNotFound: Long,
    val failedOutOfSchedule: Long
)

data class TrendPoint(val date: String?, val plates: Long, val records: Long)

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
    val cateringId: Long
)

data class ExternalScanRequest(
    val identityCard: String,
    val fullName: String,
    val mealTypeCode: String,
    val cateringId: Long
)

data class ManualScanResponse(
    val status: String,
    val message: String?,
    val employeeName: String?,
    val mealName: String?
)
