package com.eatfood.control.mobile.biometric

import android.content.Context
import android.util.Base64
import android.util.Log
import com.zkteco.android.biometric.FingerprintExceptionListener
import com.zkteco.android.biometric.module.fingerprintreader.FingerprintCaptureListener
import com.zkteco.android.biometric.module.fingerprintreader.FingerprintFactory
import com.zkteco.android.biometric.module.fingerprintreader.FingerprintSensor
import com.zkteco.android.biometric.module.fingerprintreader.exception.FingerprintException
import com.zkteco.android.biometric.core.device.TransportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Adaptador real del lector ZK9500 usando el SDK ZKTeco Biometric para Android
 * (jars en app/libs: zkandroidcore, zkandroidfpreader, zkandroidfingerservice; y
 * librerías nativas .so en src/main/jniLibs/).
 *
 * Flujo del SDK (basado en la API real de FingerprintSensor):
 *   1. FingerprintFactory.createFingerprintSensor(context, TransportType.USB, params)
 *   2. sensor.setFingerprintCaptureListener(0, listener)   // extractOK entrega la PLANTILLA
 *   3. sensor.open(0); sensor.startCapture(0)
 *   4. El listener dispara extractOK(byte[] template) en cada huella colocada
 *   5. sensor.stopCapture(0); sensor.close(0); sensor.destroy()
 *
 * La plantilla se devuelve en Base64 para enviarla al backend (identificación 1:N),
 * igual que en el frontend web.
 *
 * Nota USB: la lectura por USB-OTG requiere permiso de USB. Al conectar el lector,
 * Android puede pedirlo; si se abre el kiosco desde el evento USB_DEVICE_ATTACHED el
 * permiso se concede automáticamente para ese dispositivo.
 */
class ZkBiometricReader(private val context: Context) : BiometricReader {

    private var sensor: FingerprintSensor? = null
    private val templates = Channel<String>(Channel.CONFLATED)
    @Volatile private var deviceError = false

    override suspend fun open(onStatus: (ReaderStatus) -> Unit) = withContext(Dispatchers.IO) {
        onStatus(ReaderStatus.CONNECTING)

        // El SDK no pide el permiso de USB; debemos asegurarlo antes de abrir el sensor.
        when (val perm = UsbPermission.ensure(context)) {
            is UsbPermission.Result.NoDevice -> {
                onStatus(ReaderStatus.NO_DEVICE)
                throw BiometricException("Lector ZK9500 no detectado. Conéctelo por USB-OTG.")
            }
            is UsbPermission.Result.Denied -> {
                onStatus(ReaderStatus.ERROR)
                throw BiometricException("Permiso de USB rechazado para el lector ZK9500.")
            }
            is UsbPermission.Result.Granted -> { /* continuar */ }
        }

        try {
            val params = HashMap<String, Any>()
            val s = FingerprintFactory.createFingerprintSensor(context, TransportType.USB, params)

            s.setFingerprintCaptureListener(0, object : FingerprintCaptureListener {
                override fun captureOK(fpImage: ByteArray?) { /* imagen; no se usa */ }
                override fun captureError(e: FingerprintException?) {
                    Log.w(TAG, "captureError: ${e?.message}")
                }
                override fun extractOK(fpTemplate: ByteArray?) {
                    if (fpTemplate != null && fpTemplate.isNotEmpty()) {
                        templates.trySend(Base64.encodeToString(fpTemplate, Base64.NO_WRAP))
                    }
                }
                override fun extractError(code: Int) { Log.w(TAG, "extractError: $code") }
            })

            s.SetFingerprintExceptionListener(FingerprintExceptionListener {
                deviceError = true
                Log.w(TAG, "onDeviceException")
            })

            s.open(0)
            s.startCapture(0)
            sensor = s
            deviceError = false
            onStatus(ReaderStatus.READY)
        } catch (e: Throwable) {
            Log.e(TAG, "Error abriendo ZK9500", e)
            onStatus(ReaderStatus.ERROR)
            throw BiometricException("No se pudo abrir el lector ZK9500: ${e.message}")
        }
    }

    override suspend fun capture(timeoutMs: Long): String {
        if (sensor == null) throw BiometricException("Lector no abierto.")
        return try {
            withTimeout(timeoutMs) { templates.receive() }
        } catch (e: TimeoutCancellationException) {
            throw BiometricException("Tiempo de espera agotado para la captura.")
        }
    }

    override fun close() {
        val s = sensor ?: return
        sensor = null
        try {
            runCatching { s.stopCapture(0) }
            runCatching { s.close(0) }
            runCatching { s.destroy() }
        } catch (e: Exception) {
            Log.w(TAG, "Error cerrando ZK9500", e)
        }
    }

    class BiometricException(message: String) : Exception(message)

    companion object {
        private const val TAG = "ZkBiometricReader"

        /** ¿Está el SDK ZKFinger disponible en el classpath? */
        fun sdkAvailable(): Boolean = try {
            Class.forName("com.zkteco.android.biometric.module.fingerprintreader.FingerprintFactory")
            true
        } catch (_: Throwable) { false }
    }
}
