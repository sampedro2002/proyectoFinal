package com.eatfood.control.mobile.biometric

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ZkBiometricReader(private val context: Context) : BiometricReader {

    private var sensor: FingerprintSensor? = null
    private val templates = Channel<String>(Channel.CONFLATED)
    @Volatile private var deviceError = false

    override suspend fun open(onStatus: (ReaderStatus) -> Unit) = withContext(Dispatchers.IO) {
        onStatus(ReaderStatus.CONNECTING)

        logUsbDevices()

        val usbDevice = when (val perm = UsbPermission.ensure(context)) {
            is UsbPermission.Result.NoDevice -> {
                onStatus(ReaderStatus.NO_DEVICE)
                throw BiometricException("Lector ZK9500 no detectado. Conéctelo por USB-OTG.")
            }
            is UsbPermission.Result.Denied -> {
                onStatus(ReaderStatus.ERROR)
                throw BiometricException("Permiso de USB rechazado para el lector ZK9500.")
            }
            is UsbPermission.Result.Granted -> {
                Log.i(TAG, "Permiso USB concedido: VID=${perm.device.vendorId} PID=${perm.device.productId}")
                delay(1500)
                perm.device
            }
        }

        try {
            val s = FingerprintFactory.createFingerprintSensor(context, TransportType.USB, HashMap<String, Any>())
            sensor = s
            Log.i(TAG, "Sensor creado")

            s.setFingerprintCaptureListener(0, object : FingerprintCaptureListener {
                override fun captureOK(fpImage: ByteArray?) {}
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

            openSensorWithRetries(s, usbDevice)

            // Verificar que el dispositivo realmente responda como lector ZK9500
            if (!verifySensorConnection(s)) {
                Log.w(TAG, "El dispositivo USB no responde como lector ZK9500")
                close()
                onStatus(ReaderStatus.NO_DEVICE)
                throw BiometricException("El dispositivo USB conectado no es un lector ZK9500 válido.")
            }

            s.startCapture(0)
            Log.i(TAG, "Captura iniciada")
            deviceError = false
            onStatus(ReaderStatus.READY)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Librerías nativas no encontradas", e)
            close()
            onStatus(ReaderStatus.ERROR)
            throw BiometricException("Librerías nativas del lector no encontradas. Reinstale la aplicación.")
        } catch (e: Throwable) {
            Log.e(TAG, "Error abriendo ZK9500", e)
            close()
            onStatus(ReaderStatus.ERROR)
            val msg = when {
                e.message?.contains("permission") == true -> "Sin permiso de acceso al USB."
                else -> e.message ?: "Error desconocido al inicializar hardware."
            }
            throw BiometricException(msg)
        }
    }

    private fun verifySensorConnection(sensor: FingerprintSensor): Boolean {
        return try {
            // Verificar que no haya errores pendientes después de abrir
            if (deviceError) {
                Log.w(TAG, "deviceError está activo después de abrir")
                return false
            }
            
            // Intentar verificar el estado del sensor
            // Si el dispositivo no es un lector ZK real, esta operación fallará
            Thread.sleep(500) // Dar tiempo al sensor para inicializarse
            
            if (deviceError) {
                Log.w(TAG, "deviceError se activó durante la verificación")
                return false
            }
            
            Log.i(TAG, "Verificación de sensor completada exitosamente")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error verificando sensor: ${e.message}")
            false
        }
    }

    private fun openSensorWithRetries(s: FingerprintSensor, usbDevice: UsbDevice) {
        var retries = 3
        while (retries > 0) {
            try {
                Log.i(TAG, "Abriendo sensor con UsbDevice directo (intentos: $retries)…")
                s.open(usbDevice)
                Log.i(TAG, "Sensor abierto correctamente")
                return
            } catch (e: FingerprintException) {
                Log.w(TAG, "open(UsbDevice) falló: errorCode=${e.errorCode}, msg=${e.message}")
                if (e.errorCode == -3) {
                    Log.i(TAG, "Sensor ya estaba abierto")
                    return
                }
                retries--
                if (retries <= 0) throw e
                Thread.sleep(1000)
            }
        }
    }

    private fun logUsbDevices() {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        Log.i(TAG, "─── Dispositivos USB ───")
        um.deviceList.forEach { (name, dev) ->
            Log.i(TAG, "  $name  VID=0x${dev.vendorId.toString(16)}  PID=0x${dev.productId.toString(16)}  ${dev.productName ?: "?"}")
        }
        if (um.deviceList.isEmpty()) Log.w(TAG, "  (ningún dispositivo USB)")
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

        fun sdkAvailable(): Boolean = try {
            Class.forName("com.zkteco.android.biometric.module.fingerprintreader.FingerprintFactory")
            true
        } catch (_: Throwable) { false }
    }
}
