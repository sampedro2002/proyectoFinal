package com.eatfood.control.mobile.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.eatfood.control.mobile.BuildConfig
import com.google.gson.Gson
import com.eatfood.control.mobile.data.model.AuthResponse
import com.eatfood.control.mobile.data.model.DeviceConnectResponse
import java.util.UUID

/**
 * Almacenamiento local de sesión y configuración.
 * Equivale a `localStorage` del frontend web: tokens, usuario, sesión de kiosco,
 * URL del servidor y proveedor biométrico (zk|sim).
 *
 * Los tokens se guardan cifrados (EncryptedSharedPreferences); si el dispositivo no
 * lo soporta, cae a SharedPreferences normales para no romper la app.
 */
class SessionStore private constructor(context: Context) {

    private val gson = Gson()
    private val prefs: SharedPreferences = buildPrefs(context)

    private fun buildPrefs(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "eatfood_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("eatfood_plain", Context.MODE_PRIVATE)
    }

    // ── Tokens ────────────────────────────────────────────────────────────────
    var accessToken: String?
        get() = prefs.getString(K_ACCESS, null)
        set(v) = prefs.edit().putString(K_ACCESS, v).apply()

    var refreshToken: String?
        get() = prefs.getString(K_REFRESH, null)
        set(v) = prefs.edit().putString(K_REFRESH, v).apply()

    // ── Usuario autenticado ─────────────────────────────────────────────────--
    var user: AuthResponse?
        get() = prefs.getString(K_USER, null)?.let { runCatching { gson.fromJson(it, AuthResponse::class.java) }.getOrNull() }
        set(v) = prefs.edit().putString(K_USER, v?.let { gson.toJson(it) }).apply()

    fun saveAuth(auth: AuthResponse) {
        prefs.edit()
            .putString(K_ACCESS, auth.accessToken)
            .putString(K_REFRESH, auth.refreshToken)
            .putString(K_USER, gson.toJson(auth))
            .apply()
    }

    fun updateTokens(access: String, refresh: String) {
        prefs.edit().putString(K_ACCESS, access).putString(K_REFRESH, refresh).apply()
    }

    fun clearAuth() {
        prefs.edit().remove(K_ACCESS).remove(K_REFRESH).remove(K_USER).apply()
    }

    // ── Sesión de kiosco (dispositivo de catering) ──────────────────────────---
    var kioskSession: DeviceConnectResponse?
        get() = prefs.getString(K_KIOSK, null)?.let { runCatching { gson.fromJson(it, DeviceConnectResponse::class.java) }.getOrNull() }
        set(v) = prefs.edit().putString(K_KIOSK, v?.let { gson.toJson(it) }).apply()

    /** UID estable del dispositivo (como el deviceUid del frontend). */
    val deviceUid: String
        get() {
            var id = prefs.getString(K_DEVICE_UID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(K_DEVICE_UID, id).apply()
            }
            return id
        }

    // ── Configuración ──────────────────────────────────────────────────────---
    var serverUrl: String
        get() = prefs.getString(K_SERVER, BuildConfig.DEFAULT_SERVER_URL) ?: BuildConfig.DEFAULT_SERVER_URL
        set(v) = prefs.edit().putString(K_SERVER, normalizeUrl(v)).apply()

    /** Proveedor biométrico: "zk" (lector ZK9500 por USB-OTG) o "sim" (sin hardware). */
    var biometricProvider: String
        get() = prefs.getString(K_BIO, "zk") ?: "zk"
        set(v) = prefs.edit().putString(K_BIO, v).apply()

    companion object {
        private const val K_ACCESS = "accessToken"
        private const val K_REFRESH = "refreshToken"
        private const val K_USER = "user"
        private const val K_KIOSK = "kioskSession"
        private const val K_DEVICE_UID = "deviceUid"
        private const val K_SERVER = "serverUrl"
        private const val K_BIO = "biometricProvider"

        @Volatile private var instance: SessionStore? = null
        fun get(context: Context): SessionStore =
            instance ?: synchronized(this) {
                instance ?: SessionStore(context.applicationContext).also { instance = it }
            }

        fun normalizeUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u
        }
    }
}
