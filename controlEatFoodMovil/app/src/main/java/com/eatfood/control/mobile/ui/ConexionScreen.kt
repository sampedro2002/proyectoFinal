package com.eatfood.control.mobile.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eatfood.control.mobile.data.prefs.SessionStore
import com.eatfood.control.mobile.data.remote.ApiClient
import com.eatfood.control.mobile.data.remote.apiMessage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Pantalla "Conexión (QR)" del admin — paridad con ServerQr.jsx de la web.
 *
 * Genera y MUESTRA en pantalla el QR de la URL del servidor para que OTRO dispositivo
 * (kiosco) lo escanee desde su pantalla de Configuración y quede vinculado. Propone la
 * mejor dirección alcanzable combinando la URL con la que este teléfono ya se conectó
 * (siempre válida en la LAN) con las candidatas que reporta el backend en /server-info
 * (pública/dominio/LAN). El admin puede además ajustar la dirección a mano.
 */
@Composable
fun ConexionScreen() {
    val context = LocalContext.current
    val store = remember { SessionStore.get(context) }
    val api = remember(store.serverUrl) { ApiClient.api(context) }

    // La URL con la que este teléfono ya llegó al backend es, por definición, alcanzable
    // en la red actual: es el mejor punto de partida para vincular otros dispositivos.
    var selectedUrl by remember { mutableStateOf(store.serverUrl) }
    var candidates by remember { mutableStateOf(listOf(store.serverUrl)) }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val si = api.serverInfo()
            // Orden de utilidad, sin vacíos ni duplicados: la del teléfono primero (garantizada),
            // luego la configurada (dominio/proxy), la de la petición y las IPs de LAN.
            val merged = buildList {
                add(store.serverUrl)
                si.configuredUrl?.let { add(SessionStore.normalizeUrl(it)) }
                si.requestUrl?.let { add(SessionStore.normalizeUrl(it)) }
                si.lanUrls?.forEach { add(SessionStore.normalizeUrl(it)) }
            }.filter { it.isNotBlank() }.distinct()
            candidates = merged
        } catch (e: Exception) {
            info = e.apiMessage("No se pudieron detectar direcciones del servidor; use la actual o escríbala.")
        }
    }

    val trimmed = selectedUrl.trim()
    val isLocal = Regex("localhost|127\\.0\\.0\\.1|10\\.0\\.2\\.2").containsMatchIn(trimmed)
    val qr = remember(trimmed) { generateQr(trimmed, 640) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Conexión del dispositivo", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Muestra este QR y escanéalo desde otro dispositivo en Configuración → " +
                "«Escanear QR del servidor» para vincularlo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        // ── QR ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (qr != null && trimmed.isNotBlank()) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "QR del servidor",
                        modifier = Modifier
                            .size(260.dp)
                            .background(Color.White)
                            .padding(10.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(trimmed, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                } else {
                    Text("Escriba una dirección para generar el QR.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (isLocal) {
            Spacer(Modifier.height(12.dp))
            Text(
                "⚠ Esta dirección apunta al propio equipo y NO será alcanzable desde otro " +
                    "teléfono. Elija una IP de LAN (192.168./10./172.), pública o un dominio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Dirección editable ──
        OutlinedTextField(
            value = selectedUrl,
            onValueChange = { selectedUrl = it },
            label = { Text("Dirección del servidor (backend)") },
            placeholder = { Text("http://192.168.1.50:3000") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // ── Candidatas detectadas ──
        if (candidates.size > 1) {
            Spacer(Modifier.height(16.dp))
            Text("Direcciones detectadas", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(4.dp))
            Card(Modifier.fillMaxWidth()) {
                Column {
                    candidates.forEach { url ->
                        RowItem(
                            title = url,
                            subtitle = "",
                            trailing = if (url == trimmed) "✓ En uso" else "Usar",
                            onClick = { selectedUrl = url }
                        )
                    }
                }
            }
        }

        if (info != null) {
            Spacer(Modifier.height(12.dp))
            Text(info!!, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Requisitos: el otro dispositivo debe estar en la misma red Wi-Fi/LAN y el firewall " +
                "del servidor permitir el puerto entrante. Use https:// si el servidor tiene certificado.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Codifica [content] como un QR cuadrado de [sizePx] px. Devuelve null si falla o está vacío. */
private fun generateQr(content: String, sizePx: Int): Bitmap? {
    if (content.isBlank()) return null
    return try {
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}
