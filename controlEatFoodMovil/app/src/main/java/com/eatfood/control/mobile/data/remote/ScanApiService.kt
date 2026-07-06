package com.eatfood.control.mobile.data.remote

import com.eatfood.control.mobile.data.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Endpoints de escaneo/catering. Usan token de DISPOSITIVO (sessionToken), no JWT,
 * por eso van en un cliente Retrofit separado SIN el interceptor de autorización.
 */
interface ScanApiService {

    @POST("scan/connect")
    suspend fun connect(@Body body: DeviceConnectRequest): DeviceConnectResponse

    @POST("scan/disconnect")
    suspend fun disconnect(@Query("sessionToken") sessionToken: String): Response<Unit>

    @POST("scan")
    suspend fun scan(@Body body: ScanRequest): ScanResponse

    @POST("scan/sync")
    suspend fun sync(@Body body: SyncBatchRequest): SyncBatchResponse

    /** Consumos del día para el panel lateral del kiosco. */
    @GET("scan/today")
    suspend fun todayFeed(@Query("sessionToken") sessionToken: String): List<TodayFeedEntry>

    /** Exporta el reporte diario del Kiosk con conteo de platos. */
    @Streaming
    @GET("scan/export-today")
    suspend fun exportToday(
        @Query("sessionToken") sessionToken: String,
        @Query("format") format: String = "pdf"
    ): Response<ResponseBody>
}
