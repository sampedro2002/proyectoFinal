package com.eatfood.control.mobile.data.remote

import android.content.Context
import com.eatfood.control.mobile.data.model.AuthResponse
import com.eatfood.control.mobile.data.model.RefreshRequest
import com.eatfood.control.mobile.data.prefs.SessionStore
import com.google.gson.Gson
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Fábrica de clientes Retrofit. Reconstruye los clientes cuando cambia la URL del
 * servidor (configurable en la app). Implementa el refresco automático de JWT con la
 * misma estrategia que el interceptor del frontend (`client.js`): ante un 401 se usa
 * el refreshToken una sola vez y se reintenta la petición.
 */
object ApiClient {

    @Volatile private var api: ApiService? = null
    @Volatile private var scanApi: ScanApiService? = null
    @Volatile private var builtFor: String? = null

    private val gson = Gson()

    fun api(context: Context): ApiService = build(context).first
    fun scanApi(context: Context): ScanApiService = build(context).second

    /** Fuerza reconstrucción (p. ej. al cambiar la URL del servidor en ajustes). */
    fun reset() {
        api = null; scanApi = null; builtFor = null
    }

    @Synchronized
    private fun build(context: Context): Pair<ApiService, ScanApiService> {
        val store = SessionStore.get(context)
        val base = store.serverUrl + "/api/"
        if (api == null || scanApi == null || builtFor != base) {
            val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }

            // Cliente con JWT (auth + refresh) para la API protegida.
            val authedClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(AuthInterceptor(store))
                .authenticator(TokenAuthenticator(store, base, gson))
                .addInterceptor(logging)
                .build()

            // Cliente sin JWT para /scan (usa sessionToken de dispositivo).
            val plainClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            api = Retrofit.Builder()
                .baseUrl(base)
                .client(authedClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(ApiService::class.java)

            scanApi = Retrofit.Builder()
                .baseUrl(base)
                .client(plainClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(ScanApiService::class.java)

            builtFor = base
        }
        return api!! to scanApi!!
    }

    /** Añade el header Authorization si hay accessToken. */
    private class AuthInterceptor(private val store: SessionStore) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val token = store.accessToken
            val out: Request = if (!token.isNullOrBlank())
                req.newBuilder().header("Authorization", "Bearer $token").build()
            else req
            return chain.proceed(out)
        }
    }

    /** Ante un 401, intenta renovar tokens con el refreshToken (una sola vez). */
    private class TokenAuthenticator(
        private val store: SessionStore,
        private val base: String,
        private val gson: Gson
    ) : Authenticator {
        private val refreshClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).build()

        override fun authenticate(route: Route?, response: Response): Request? {
            if (responseCount(response) >= 2) return null   // ya reintentado
            val refresh = store.refreshToken ?: run { store.clearAuth(); return null }

            synchronized(this) {
                val current = store.accessToken
                // Si otro hilo ya renovó, reintenta con el token nuevo.
                val authHeader = response.request.header("Authorization")
                if (current != null && authHeader != "Bearer $current") {
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer $current").build()
                }

                val body = gson.toJson(RefreshRequest(refresh))
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(base + "auth/refresh").post(body).build()
                refreshClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) { store.clearAuth(); return null }
                    val auth = gson.fromJson(res.body?.string(), AuthResponse::class.java)
                    store.updateTokens(auth.accessToken, auth.refreshToken)
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer ${auth.accessToken}").build()
                }
            }
        }

        private fun responseCount(response: Response): Int {
            var r: Response? = response; var c = 1
            while (r?.priorResponse != null) { c++; r = r.priorResponse }
            return c
        }
    }
}
