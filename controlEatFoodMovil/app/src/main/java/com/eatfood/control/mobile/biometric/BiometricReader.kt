package com.eatfood.control.mobile.biometric

import android.content.Context
import com.eatfood.control.mobile.data.prefs.SessionStore

/** Estado del lector, equivalente a los READER_STATUS del Kiosk web. */
enum class ReaderStatus { CONNECTING, READY, NO_DEVICE, ERROR, DISCONNECTED, SIM }

/**
 * Abstracción del lector biométrico. Igual que en el backend (`zk` | `sim`), la app
 * elige el proveedor en tiempo de ejecución. La salida es SIEMPRE una plantilla
 * (template) en Base64 que se envía al backend para la identificación 1:N.
 */
interface BiometricReader {
    /** Abre el dispositivo. Lanza excepción si no se puede. */
    suspend fun open(onStatus: (ReaderStatus) -> Unit = {})

    /** Captura una huella y devuelve la plantilla en Base64. Suspende hasta capturar o timeout. */
    suspend fun capture(timeoutMs: Long = 20_000): String

    /** Cierra el dispositivo y libera recursos. */
    fun close()

    companion object {
        /** Crea el lector según la preferencia guardada (sim por defecto). */
        fun create(context: Context): BiometricReader {
            val provider = SessionStore.get(context).biometricProvider
            return if (provider.equals("zk", ignoreCase = true)) {
                ZkBiometricReader(context)
            } else {
                SimBiometricReader()
            }
        }
    }
}
