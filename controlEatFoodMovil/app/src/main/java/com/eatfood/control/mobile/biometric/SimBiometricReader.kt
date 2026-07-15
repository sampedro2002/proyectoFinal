package com.eatfood.control.mobile.biometric

import kotlinx.coroutines.delay

/**
 * Lector simulado para pruebas sin hardware.
 */
class SimBiometricReader : BiometricReader {
    override suspend fun open(onStatus: (ReaderStatus) -> Unit) {
        onStatus(ReaderStatus.CONNECTING)
        delay(1000)
        onStatus(ReaderStatus.SIM)
    }

    override suspend fun capture(timeoutMs: Long): String {
        delay(2000)
        // Retorna una plantilla dummy
        return "U01NIDAyIDQ4IDQ4IDAgMCAwIDAgMCAwIDAgMCAwIDAgMCAwIDAgMCAwIDAg"
    }

    override suspend fun captureForEnroll(timeoutMs: Long, onProgress: (Int, Int) -> Unit): String {
        val total = 3
        for (i in 1..total) {
            onProgress(i, total)
            delay(1500)
        }
        return capture(timeoutMs)
    }

    override fun close() {}
}
