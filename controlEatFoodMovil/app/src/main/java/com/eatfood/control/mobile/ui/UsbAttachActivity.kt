package com.eatfood.control.mobile.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Trampolín invisible para USB_DEVICE_ATTACHED (conectar el lector ZK9500).
 *
 * Antes el evento lo recibía KioskActivity directamente: al enchufar el lector
 * con el panel admin abierto, Android traía el kiosco al frente y (por su
 * launchMode singleTask) destruía MainActivity, expulsando al usuario del admin.
 *
 * Esta actividad decide sin mover al usuario de donde está:
 *  - App ya abierta (admin o kiosco): no hace nada; la pantalla visible tiene
 *    detección activa del lector y lo toma sola ("Conectando… → Conectado").
 *  - App cerrada: abre el kiosco (comportamiento de siempre).
 *
 * En ambos casos, el lanzamiento vía ATTACHED le concede a la app el permiso
 * USB del dispositivo sin mostrar el diálogo del sistema.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // isTaskRoot == true -> la app estaba cerrada y este trampolín creó la
        // tarea; abrir el kiosco. Si la app ya estaba abierta, el trampolín se
        // apila sobre la tarea existente (isTaskRoot == false) y solo se cierra.
        if (isTaskRoot) {
            startActivity(Intent(this, com.eatfood.control.mobile.ui.kiosk.KioskActivity::class.java))
        }
        finish()
    }
}
