package com.eatfood.control.mobile.biometric

import android.util.Base64
import kotlinx.coroutines.delay

/**
 * Lector simulado (equivale a VITE_BIOMETRIC_SIM=true del frontend). Permite probar
 * todo el flujo de extremo a extremo sin hardware ZK9500 cuando el backend corre con
 * `app.biometric.provider=sim`.
 *
 * La plantilla es **determinista** (los mismos 512 bytes en cada captura): así, lo que
 * se enrola y lo que se escanea coinciden y el matcher `sim` del backend —que compara
 * por igualdad exacta de bytes— puede identificar al empleado. Con una plantilla
 * aleatoria el escaneo nunca coincidiría con lo enrolado.
 *
 * Implicación: en modo simulado todos los dedos comparten la misma plantilla, por lo que
 * conviene enrolar UN solo empleado para la demo; cualquier escaneo lo identificará.
 * Si no hay ninguna huella enrolada, el backend responde HUELLA NO ENCONTRADA (esperado).
 */
class SimBiometricReader : BiometricReader {

    override suspend fun open(onStatus: (ReaderStatus) -> Unit) {
        onStatus(ReaderStatus.SIM)
    }

    override suspend fun capture(timeoutMs: Long): String {
        // Simula el tiempo que tarda una persona en colocar el dedo.
        delay(1200)
        val bytes = ByteArray(512) { (it % 256).toByte() }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    override fun close() { /* nada que liberar */ }
}
