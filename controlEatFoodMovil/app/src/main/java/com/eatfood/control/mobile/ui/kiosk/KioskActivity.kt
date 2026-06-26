package com.eatfood.control.mobile.ui.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eatfood.control.mobile.biometric.BiometricReader
import com.eatfood.control.mobile.biometric.ReaderStatus
import com.eatfood.control.mobile.data.db.AppDatabase
import com.eatfood.control.mobile.data.db.PendingScan
import com.eatfood.control.mobile.data.model.TodayFeedEntry
import com.eatfood.control.mobile.data.model.*
import com.eatfood.control.mobile.data.prefs.SessionStore
import com.eatfood.control.mobile.data.remote.ApiClient
import com.eatfood.control.mobile.data.remote.apiMessage
import com.eatfood.control.mobile.ui.theme.*
import com.eatfood.control.mobile.util.ToneFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class KioskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { EatFoodTheme { KioskRoot() } }
    }
}

@Composable
private fun KioskRoot() {
    val context = LocalContext.current
    val store = remember { SessionStore.get(context) }
    var session by remember { mutableStateOf(store.kioskSession) }

    if (session == null) {
        ConnectPanel(onConnected = { session = store.kioskSession })
    } else {
        KioskPanel(session!!, onDisconnect = { session = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectPanel(onConnected: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SessionStore.get(context) }
    val scanApi = remember { ApiClient.scanApi(context) }
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().widthIn(max = 440.dp)) {
            Column(
                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🖐 CATERING", style = MaterialTheme.typography.headlineSmall)
                Text("Conecte este dispositivo al sistema",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(user, { user = it }, label = { Text("Usuario de catering") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Contraseña") }, singleLine = true,
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Ocultar" else "Ver") } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(deviceName, { deviceName = it },
                    label = { Text("Nombre del dispositivo (${Build.MODEL})") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        if (user.isBlank() || pass.isBlank()) { error = "Ingrese usuario y contraseña"; return@Button }
                        error = null; busy = true
                        scope.launch {
                            try {
                                val res = scanApi.connect(
                                    DeviceConnectRequest(user.trim(), pass, store.deviceUid,
                                        deviceName.ifBlank { Build.MODEL })
                                )
                                store.kioskSession = res
                                onConnected()
                            } catch (e: Exception) {
                                error = e.apiMessage("No se pudo conectar el dispositivo")
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Conectar") }
                Text(
                    "ℹ️ Lector ZK9500: conéctalo por USB-OTG.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun KioskPanel(session: DeviceConnectResponse, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SessionStore.get(context) }
    val scanApi = remember { ApiClient.scanApi(context) }
    val dao = remember { AppDatabase.get(context).pendingScanDao() }
    val scope = rememberCoroutineScope()

    var online by remember { mutableStateOf(isOnline(context)) }
    var queued by remember { mutableStateOf(0) }
    var readerStatus by remember { mutableStateOf(ReaderStatus.CONNECTING) }
    var lastError by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ScanResponse?>(null) }
    var feed by remember { mutableStateOf<List<TodayFeedEntry>>(emptyList()) }
    val usbDisconnected = remember { AtomicBoolean(false) }
    var currentReader by remember { mutableStateOf<BiometricReader?>(null) }

    suspend fun refreshQueued() { queued = runCatching { dao.count() }.getOrDefault(0) }

    suspend fun syncQueue() {
        val items = dao.pending()
        if (items.isEmpty()) return
        runCatching {
            val records = items.map { ScanRequest(templateB64 = it.templateB64, mealTypeCode = it.mealTypeCode,
                clientUuid = it.clientUuid, offline = true, consumedAt = it.consumedAt) }
            val res = scanApi.sync(SyncBatchRequest(session.sessionToken, records))
            res.results?.forEach { r -> if (r.status != "ERROR" && r.clientUuid != null) dao.remove(r.clientUuid) }
        }
        refreshQueued()
    }

    suspend fun processCapture(template: String) {
        val clientUuid = UUID.randomUUID().toString()
        val consumedAt = OffsetDateTime.now().toString()
        if (online) {
            try {
                result = scanApi.scan(ScanRequest(session.sessionToken, template, null, clientUuid, false, consumedAt))
                if (result?.status == "SUCCESS") ToneFeedback.success() else ToneFeedback.error()
                return
            } catch (_: Exception) { /* degradar a offline */ }
        }
        dao.enqueue(PendingScan(clientUuid, template, null, consumedAt))
        refreshQueued()
        result = ScanResponse("QUEUED", "REGISTRO EN COLA (OFFLINE)", null, null, null, consumedAt)
        ToneFeedback.success()
    }

    // Feed de consumos del día (equivalente al feedPanel del Kiosk web)
    LaunchedEffect(Unit) {
        while (isActive) {
            runCatching { feed = scanApi.todayFeed(session.sessionToken) }
            delay(10_000)
        }
    }

    // Monitoreo de red
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = false }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }

    // Monitoreo de desconexión USB
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    usbDisconnected.set(true)
                    readerStatus = ReaderStatus.DISCONNECTED
                    lastError = ""
                    result = null
                    runCatching { currentReader?.close() }
                    currentReader = null
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Sincronización periódica
    LaunchedEffect(online) {
        while (isActive) {
            refreshQueued()
            if (online) syncQueue()
            delay(15_000)
        }
    }

    // Bucle de captura
    LaunchedEffect(Unit) {
        while (isActive) {
            usbDisconnected.set(false)
            readerStatus = ReaderStatus.CONNECTING
            lastError = ""
            val reader = BiometricReader.create(context)
            currentReader = reader
            try {
                reader.open { st ->
                    if (!usbDisconnected.get()) readerStatus = st
                }
            } catch (e: Exception) {
                currentReader = null
                lastError = e.message ?: "Error desconocido"
                if (readerStatus == ReaderStatus.CONNECTING) readerStatus = ReaderStatus.ERROR
                delay(5_000); continue
            }
            while (isActive && !usbDisconnected.get()) {
                try {
                    result = null
                    val template = reader.capture(20_000)
                    if (!usbDisconnected.get()) processCapture(template)
                    delay(10_000)
                    result = null
                } catch (e: Exception) {
                    if (usbDisconnected.get()) break
                    delay(800)
                    if (readerStatus == ReaderStatus.CONNECTING) readerStatus = ReaderStatus.ERROR
                }
            }
            runCatching { reader.close() }
            currentReader = null
        }
    }

    val ok = result?.status == "SUCCESS" || result?.status == "QUEUED"
    val bg = when {
        result == null -> Bg
        ok -> Success
        else -> ErrorRed
    }

    Surface(color = bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // ── Pills superiores ─────────────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ReaderPill(readerStatus, Modifier)
                Text(
                    (if (online) "En línea" else "Sin conexión") + if (queued > 0) " · $queued en cola" else "",
                    color = if (online) Success else Warning
                )
            }

            // ── Centro: resultado o espera ───────────────────────────────────────
            val r = result
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (r == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(session.cateringName?.uppercase() ?: "CATERING",
                            style = MaterialTheme.typography.headlineMedium)
                        Text("🖐️", fontSize = 110.sp, modifier = Modifier.padding(vertical = 16.dp))
                        Text(
                            when (readerStatus) {
                                ReaderStatus.CONNECTING -> "Conectando lector…"
                                ReaderStatus.ERROR -> "Error: $lastError"
                                ReaderStatus.NO_DEVICE -> "Conecte el lector por USB-OTG"
                                ReaderStatus.DISCONNECTED -> "Reconectando…"
                                else -> "Esperando huella…"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when (r.status) {
                                "SUCCESS" -> "✓ REGISTRO EXITOSO"
                                "QUEUED" -> "✓ REGISTRO EN COLA"
                                else -> "✕ ${r.message ?: "ERROR"}"
                            },
                            color = Color.White, fontSize = 30.sp, textAlign = TextAlign.Center
                        )
                        r.employeeName?.let { Text(it, color = Color.White, fontSize = 26.sp, modifier = Modifier.padding(top = 12.dp)) }
                        r.mealName?.let { Text(it, color = Color.White, fontSize = 18.sp) }
                        r.plates?.let { Text("Platos: $it", color = Color.White, fontSize = 18.sp) }
                        if (r.status == "QUEUED") Text("Se sincronizará al recuperar la conexión.",
                            color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            // ── Panel de consumos del día (equivalente al feedPanel del Kiosk web) ─
            if (feed.isNotEmpty()) {
                TodayFeedPanel(feed, Modifier.heightIn(max = 180.dp))
                Spacer(Modifier.height(8.dp))
            }

            // ── Fila inferior ────────────────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    scope.launch { runCatching { scanApi.disconnect(session.sessionToken) } }
                    store.kioskSession = null
                    onDisconnect()
                }) { Text("Desconectar", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun ReaderPill(status: ReaderStatus, modifier: Modifier) {
    val (text, color) = when (status) {
        ReaderStatus.CONNECTING -> "Conectando lector…" to Warning
        ReaderStatus.READY -> "Lector listo ✓" to Success
        ReaderStatus.NO_DEVICE -> "Lector no detectado" to ErrorRed
        ReaderStatus.ERROR -> "Error de conexión al lector" to ErrorRed
        ReaderStatus.DISCONNECTED -> "Lector desconectado" to ErrorRed
    }
    Text(text, color = color, modifier = modifier)
}

/** Panel lateral de consumos del día (espejo del feedPanel del Kiosk web). */
@Composable
private fun TodayFeedPanel(feed: List<TodayFeedEntry>, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "Hoy — ${feed.size} ${if (feed.size == 1) "comensal" else "comensales"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn {
                items(feed.takeLast(8)) { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            e.employeeName ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            buildString {
                                e.mealName?.let { append(it) }
                                e.time?.let {
                                    val t = it.substringAfter('T').take(5)
                                    if (t.isNotEmpty()) append(" · $t")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
