package com.eatfood.control.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eatfood.control.mobile.biometric.ReaderStatus
import com.eatfood.control.mobile.data.prefs.SessionStore
import com.eatfood.control.mobile.data.remote.ApiClient
import com.eatfood.control.mobile.ui.theme.ErrorRed
import com.eatfood.control.mobile.ui.theme.OnSurface
import com.eatfood.control.mobile.ui.theme.Sim
import com.eatfood.control.mobile.ui.theme.Success
import com.eatfood.control.mobile.ui.theme.Warning
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Fila genérica título/subtítulo/acción reutilizada por las pantallas de lista. */
@Composable
fun RowItem(
    title: String,
    subtitle: String,
    trailing: String = "",
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface
) {
    Surface(
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                if (subtitle.isNotBlank()) Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trailing.isNotEmpty()) Text(
                trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    HorizontalDivider()
}

@Composable
fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Pill de estado del lector biométrico ("ZKTeco Conectado ✓", etc.).
 * Compartida entre el kiosco y la pantalla de huellas del admin para que
 * ambas muestren la misma detección en vivo del ZK9500.
 */
@Composable
fun ReaderStatusPill(status: ReaderStatus, modifier: Modifier = Modifier) {
    val (text, color) = when (status) {
        ReaderStatus.CONNECTING -> "Conectando…" to Warning
        ReaderStatus.READY -> "ZKTeco Conectado ✓" to Success
        ReaderStatus.NO_DEVICE -> "Lector no detectado" to ErrorRed
        ReaderStatus.ERROR -> "Error de Hardware" to ErrorRed
        ReaderStatus.DISCONNECTED -> "Desconectado" to ErrorRed
        ReaderStatus.SIM -> "Modo Simulado" to Sim
    }

    Surface(
        modifier = modifier.clip(CircleShape),
        color = Color.Black.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(text, color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Aviso de "activación" para el admin/RRHH desde el móvil: equivalente a
 * BiometricReaderBanner.jsx de la web. Se queda fijo arriba de CUALQUIER
 * pantalla del panel (se monta una sola vez en MainScaffold, no por pantalla)
 * hasta que el ZK9500 se conecte UNA VEZ al SERVIDOR tras una instalación,
 * reinstalación o reinicio. Sondea /fingerprints/biometric-status y usa
 * readerEverConnected (no readerConnected): una vez detectado, el aviso NO
 * vuelve a aparecer aunque el lector se desconecte después durante el uso
 * normal -- el backend resetea esa bandera solo, en su próximo arranque.
 */
@Composable
fun BiometricReaderBanner() {
    val context = LocalContext.current
    val api = remember(SessionStore.get(context).serverUrl) { ApiClient.api(context) }
    // null = aún no llegó la primera respuesta (no se muestra nada, para no
    // parpadear el aviso si el lector ya estaba conectado desde el arranque).
    var everConnected by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                val status = api.biometricStatus()
                everConnected = status.readerEverConnected
            } catch (_: Exception) {
                // Sin respuesta (backend caído, sesión sin rol admitido, etc.):
                // no es el problema que este aviso debe reportar.
            }
            if (everConnected == true) break
            delay(5_000)
        }
    }

    if (everConnected != false) return

    Surface(color = ErrorRed, modifier = Modifier.fillMaxWidth()) {
        Text(
            "⚠ CONECTE EL LECTOR ZKTECO AL SERVIDOR PARA PODER USAR EL SERVICIO BIOMÉTRICO",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp)
        )
    }
}
