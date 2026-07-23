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

    // ── Huellas ───────────────────────────────────────────────────────────────
    @GET("fingerprints/employee/{employeeId}")
    suspend fun fingerprints(@Path("employeeId") employeeId: Long): List<FingerprintResponse>

    @POST("fingerprints/enroll")
    suspend fun enroll(@Body body: EnrollRequest): FingerprintResponse

    @DELETE("fingerprints/{id}")
    suspend fun deleteFingerprint(@Path("id") id: Long): Response<Unit>

    // ── Catálogos ───────────────────────────────────────────────────────────--
    @GET("restaurants")
    suspend fun restaurants(): List<RestaurantResponse>
    @POST("restaurants")
    suspend fun createRestaurant(@Body body: RestaurantRequest): RestaurantResponse
    @PUT("restaurants/{id}")
    suspend fun updateRestaurant(@Path("id") id: Long, @Body body: RestaurantRequest): RestaurantResponse

    // ── Usuarios (solo ADMIN, mismas rutas que la página Usuarios de la web) ──
    @GET("users")
    suspend fun users(): List<UserResponse>
    @GET("users/roles")
    suspend fun roles(): List<RoleResponse>
    @POST("users")
    suspend fun createUser(@Body body: UserRequest): UserResponse
    @PUT("users/{id}")
    suspend fun updateUser(@Path("id") id: Long, @Body body: UserRequest): UserResponse
    @POST("users/{id}/password")
    suspend fun resetUserPassword(@Path("id") id: Long, @Body body: PasswordResetRequest): Response<Unit>
    @PATCH("users/{id}/enabled")
    suspend fun setUserEnabled(@Path("id") id: Long, @Body body: EnabledRequest): UserResponse

    @GET("schedules")
    suspend fun schedules(): List<ScheduleResponse>
    @POST("schedules")
    suspend fun saveGeneralSchedule(@Body body: GeneralScheduleRequest): ScheduleResponse

    // ── Reportes ───────────────────────────────────────────────────────────────
    @GET("reports/dashboard")
    suspend fun dashboard(@Query("date") date: String? = null): DashboardStats

    @GET("reports/trend")
    suspend fun trend(@Query("from") from: String, @Query("to") to: String): List<TrendPoint>

    @GET("reports/consumptions")
    suspend fun consumptions(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("restaurantId") restaurantId: Long? = null,
        @Query("employeeId") employeeId: Long? = null,
        @Query("showCancelled") showCancelled: Boolean = false
    ): List<ConsumptionRow>

    @GET("reports/export")
    @Streaming
    suspend fun export(
        @Query("format") format: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("restaurantId") restaurantId: Long? = null,
        @Query("showCancelled") showCancelled: Boolean = false
    ): Response<ResponseBody>

    // ── Información del servidor (pantalla Conexión / QR, solo ADMIN) ───────────
    @GET("server-info")
    suspend fun serverInfo(): ServerInfo

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

    /** Comidas permitidas y aún no consumidas hoy por el empleado (para pre-seleccionar en el registro manual). */
    @GET("manual-consumptions/availability/{employeeId}")
    suspend fun mealAvailability(@Path("employeeId") employeeId: Long): MealAvailabilityResponse

    // ── Edición de consumos manuales ───────────────────────────────────────
    @GET("manual-consumptions")
    suspend fun manualConsumptionsList(
        @Query("search") search: String? = null,
        @Query("restaurantId") restaurantId: Long? = null,
        @Query("cancelled") cancelled: Boolean? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Page<ConsumptionDetailResponse>

    @GET("manual-consumptions/{id}")
    suspend fun manualConsumptionDetail(@Path("id") id: Long): ConsumptionDetailResponse

    @PUT("manual-consumptions/{id}")
    suspend fun updateManualConsumption(
        @Path("id") id: Long,
        @Body body: UpdateManualConsumptionRequest
    ): ConsumptionDetailResponse

    @POST("manual-consumptions/{id}/cancel")
    suspend fun cancelManualConsumption(@Path("id") id: Long): Response<Unit>

    @POST("manual-consumptions/{id}/uncancel")
    suspend fun uncancelManualConsumption(@Path("id") id: Long): Response<Unit>
}
