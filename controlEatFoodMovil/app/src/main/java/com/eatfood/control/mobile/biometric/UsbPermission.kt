package com.eatfood.control.mobile.biometric

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.eatfood.control.mobile.EatFoodApp
import com.eatfood.control.mobile.R
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

    private val ZK_VENDOR_IDS = setOf(6997, 1947, 0x1B55, 0x079B, 0x28E9, 0x0783)

    private val ZK_PRODUCT_KEYWORDS = listOf("zk", "fingerprint", "biometric", "slk", "9500")

    sealed class Result {
        data class Granted(val device: UsbDevice) : Result()
        object NoDevice : Result()
        object Denied : Result()
    }

    fun findReader(context: Context): UsbDevice? {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val allDevices = um.deviceList.values.toList()
        Log.i(TAG, "Buscando lector ZK entre ${allDevices.size} dispositivo(s) USB…")
        allDevices.forEach { dev ->
            Log.i(TAG, "  → VID=${dev.vendorId} (0x${dev.vendorId.toString(16)})  PID=${dev.productId} (0x${dev.productId.toString(16)})  producto='${dev.productName ?: "?"}'  fabricante='${dev.manufacturerName ?: "?"}'")
        }
        val byVid = allDevices.firstOrNull { it.vendorId in ZK_VENDOR_IDS }
        if (byVid != null) {
            Log.i(TAG, "Lector encontrado por VID: ${byVid.vendorId}")
            return byVid
        }
        val byName = allDevices.firstOrNull { dev ->
            val name = (dev.productName ?: "").lowercase()
            val mfg = (dev.manufacturerName ?: "").lowercase()
            ZK_PRODUCT_KEYWORDS.any { it in name || it in mfg }
        }
        if (byName != null) {
            Log.i(TAG, "Lector encontrado por nombre: '${byName.productName}' (fabricante: '${byName.manufacturerName}')")
            return byName
        }
        Log.w(TAG, "No se encontró ningún lector ZK entre los dispositivos USB conectados")
        return null
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
            showNotification(context)
            um.requestPermission(device, pending)
        }
    }

    private fun showNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, EatFoodApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Asegúrate de que este icono existe o usa uno válido
            .setContentTitle("Lector USB Detectado")
            .setContentText("Pulsa para conceder permisos al lector biométrico ZKTeco.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}
