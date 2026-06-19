package com.eatfood.control.mobile.biometric

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Manejo del permiso de acceso al lector ZK9500 conectado por USB-OTG.
 *
 * Android exige permiso explícito del usuario para que una app acceda a un dispositivo
 * USB. Solo se concede automáticamente cuando la actividad se lanza desde el evento
 * `USB_DEVICE_ATTACHED` (al conectar el lector con la app cerrada). Si el usuario abre
 * la pantalla de catering primero y luego conecta el lector —o lo abre desde el menú—
 * hay que pedir el permiso manualmente con `UsbManager.requestPermission`, o el SDK
 * fallará al abrir el sensor.
 *
 * Los VID deben coincidir con `res/xml/device_filter.xml`.
 */
object UsbPermission {
    private const val TAG = "UsbPermission"
    private const val ACTION_USB_PERMISSION = "com.eatfood.control.mobile.USB_PERMISSION"

    /** VIDs de ZKTeco (0x1B55 y 0x079B). Mantener en sync con device_filter.xml. */
    private val ZK_VENDOR_IDS = setOf(6997, 1947)

    sealed class Result {
        data class Granted(val device: UsbDevice) : Result()
        /** El lector no está conectado por USB-OTG. */
        object NoDevice : Result()
        /** El usuario rechazó el permiso de acceso al dispositivo. */
        object Denied : Result()
    }

    /** Busca el lector ZK conectado por USB-OTG, o null si no hay ninguno. */
    fun findReader(context: Context): UsbDevice? {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return um.deviceList.values.firstOrNull { it.vendorId in ZK_VENDOR_IDS }
    }

    /**
     * Asegura que la app tenga permiso para acceder al lector ZK. Suspende hasta que el
     * usuario responda el diálogo del sistema (si hace falta pedirlo).
     */
    suspend fun ensure(context: Context): Result {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return Result.NoDevice
        val device = findReader(context) ?: return Result.NoDevice
        if (um.hasPermission(device)) return Result.Granted(device)

        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "Permiso USB ${if (granted) "concedido" else "rechazado"}")
                    if (cont.isActive) {
                        cont.resume(if (granted) Result.Granted(device) else Result.Denied)
                    }
                }
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(context, 0, intent, flags)

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

            Log.i(TAG, "Solicitando permiso USB para ${device.deviceName} (VID ${device.vendorId})")
            um.requestPermission(device, pending)
        }
    }
}
