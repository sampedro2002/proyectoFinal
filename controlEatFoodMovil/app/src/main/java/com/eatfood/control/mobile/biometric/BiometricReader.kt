package com.eatfood.control.mobile.biometric

import android.content.Context

/** Estado del lector, equivalente a los READER_STATUS del Kiosk web. */
enum class ReaderStatus { CONNECTING, READY, NO_DEVICE, ERROR, DISCONNECTED }

/**
 * Abstracción del lector biométrico. La salida es SIEMPRE una plantilla
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
        /** Crea el lector ZK9500. */
        fun create(context: Context): BiometricReader = ZkBiometricReader(context)
    }
}
