package com.eatfood.control.mobile.biometric

import android.content.Context

/** Estado del lector, equivalente a los READER_STATUS del Kiosk web. */
enum class ReaderStatus { CONNECTING, READY, NO_DEVICE, ERROR, DISCONNECTED, SIM }

/**
 * Abstracción del lector biométrico. La salida es SIEMPRE una plantilla
 * (template) en Base64 que se envía al backend para la identificación 1:N.
 */
interface BiometricReader {
    /** Abre el dispositivo. Lanza excepción si no se puede. */
    suspend fun open(onStatus: (ReaderStatus) -> Unit = {})

    /** Captura una huella y devuelve la plantilla en Base64. Suspende hasta capturar o timeout. */
    suspend fun capture(timeoutMs: Long = 20_000): String

    /**
     * Captura de ENROLAMIENTO: 3 muestras del mismo dedo (con levantamiento entre cada
     * una) fusionadas en una sola plantilla, igual que el modo "register" de la web
     * (ZkFingerWebSocketHandler.captureRegisterMode + ZKFPM_DBMerge). `onProgress`
     * notifica cuántas muestras van CAPTURADAS (0..total); se emite 0 al empezar y se
     * incrementa después de registrar cada muestra, como el capture_progress de la web.
     * Devuelve la plantilla fusionada en Base64, lista para POST /fingerprints/enroll.
     */
    suspend fun captureForEnroll(
        timeoutMs: Long = 20_000,
        onProgress: (sample: Int, total: Int) -> Unit = { _, _ -> }
    ): String

    /** Cierra el dispositivo y libera recursos. */
    fun close()

    companion object {
        /** Crea el lector según la preferencia guardada (zk por defecto). */
        fun create(context: Context): BiometricReader {
            val store = com.eatfood.control.mobile.data.prefs.SessionStore.get(context)
            return if (store.biometricProvider == "zk") {
                ZkBiometricReader(context)
            } else {
                SimBiometricReader()
            }
        }
    }
}
