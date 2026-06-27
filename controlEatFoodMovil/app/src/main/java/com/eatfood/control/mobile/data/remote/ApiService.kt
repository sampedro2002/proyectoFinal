package com.eatfood.control.mobile.data.remote

import com.eatfood.control.mobile.data.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * API REST del backend (mismas rutas que consume el frontend web).
 * Base: <serverUrl>/api  ·  Auth: Bearer <accessToken> (salvo /auth y /scan).
 */
interface ApiService {

    // ── Auth ──────────────────────────────────────────────────────────────────
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>

    // ── Empleados ──────────────────────────────────────────────────────────────
    @GET("employees")
    suspend fun employees(
        @Query("term") term: String?,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100
    ): Page<EmployeeResponse>

    @GET("employees/{id}")
    suspend fun employee(@Path("id") id: Long): EmployeeResponse

    @POST("employees")
    suspend fun createEmployee(@Body body: EmployeeRequest): EmployeeResponse

    @PUT("employees/{id}")
    suspend fun updateEmployee(@Path("id") id: Long, @Body body: EmployeeRequest): EmployeeResponse

    @DELETE("employees/{id}")
    suspend fun deleteEmployee(@Path("id") id: Long): Response<Unit>

    // ── Huellas ───────────────────────────────────────────────────────────────
    @GET("fingerprints/employee/{employeeId}")
    suspend fun fingerprints(@Path("employeeId") employeeId: Long): List<FingerprintResponse>

    @POST("fingerprints/enroll")
    suspend fun enroll(@Body body: EnrollRequest): FingerprintResponse

    @DELETE("fingerprints/{id}")
    suspend fun deleteFingerprint(@Path("id") id: Long): Response<Unit>

    // ── Catálogos ───────────────────────────────────────────────────────────--
    @GET("positions")
    suspend fun positions(): List<PositionResponse>
    @POST("positions")
    suspend fun createPosition(@Body body: PositionRequest): PositionResponse
    @PUT("positions/{id}")
    suspend fun updatePosition(@Path("id") id: Long, @Body body: PositionRequest): PositionResponse

    @GET("caterings")
    suspend fun caterings(): List<CateringResponse>
    @POST("caterings")
    suspend fun createCatering(@Body body: CateringRequest): CateringResponse
    @PUT("caterings/{id}")
    suspend fun updateCatering(@Path("id") id: Long, @Body body: CateringRequest): CateringResponse

    @GET("meal-types")
    suspend fun mealTypes(): List<MealTypeResponse>

    @GET("schedules")
    suspend fun schedules(): List<ScheduleResponse>
    @POST("schedules")
    suspend fun saveSchedule(@Body body: ScheduleRequest): ScheduleResponse

    // ── Reportes ───────────────────────────────────────────────────────────────
    @GET("reports/dashboard")
    suspend fun dashboard(@Query("date") date: String? = null): DashboardStats

    @GET("reports/trend")
    suspend fun trend(@Query("from") from: String, @Query("to") to: String): List<TrendPoint>

    @GET("reports/consumptions")
    suspend fun consumptions(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("cateringId") cateringId: Long? = null,
        @Query("mealTypeId") mealTypeId: Long? = null,
        @Query("employeeId") employeeId: Long? = null
    ): List<ConsumptionRow>

    @GET("reports/export")
    @Streaming
    suspend fun export(
        @Query("format") format: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("cateringId") cateringId: Long? = null,
        @Query("mealTypeId") mealTypeId: Long? = null
    ): Response<ResponseBody>

    // ── Auditoría ──────────────────────────────────────────────────────────────
    @GET("audit")
    suspend fun audit(
        @Query("entity") entity: String?,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
        @Query("sort") sort: String = "createdAt,desc"
    ): Page<AuditRow>

    // ── Registro Manual de Consumos ────────────────────────────────────────────
    @POST("manual-consumptions")
    suspend fun manualScan(@Body body: ManualScanRequest): ManualScanResponse

    @POST("manual-consumptions/external")
    suspend fun manualScanExternal(@Body body: ExternalScanRequest): ManualScanResponse
}
