package com.eatfood.control.mobile.ui.kiosk

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import com.eatfood.control.mobile.data.remote.apiError
import com.eatfood.control.mobile.data.remote.apiMessage
import com.eatfood.control.mobile.ui.theme.*
import com.eatfood.control.mobile.util.ToneFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
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
        KioskPanel(initialSession = session!!, onDisconnect = { session = null })
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
                Image(
                    painter = painterResource(com.eatfood.control.mobile.R.drawable.ic_logo),
                    contentDescription = "EatFood",
                    modifier = Modifier.size(72.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("RESTAURANTE", style = MaterialTheme.typography.headlineSmall)
                Text("Conecte este dispositivo al sistema",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(user, { user = it }, label = { Text("Usuario de restaurante") },
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
                TextButton(onClick = {
                    context.startActivity(Intent(context, com.eatfood.control.mobile.ui.MainActivity::class.java))
                }) { Text("Acceso Admin") }
            }
        }
    }
}

@Composable
private fun KioskPanel(initialSession: DeviceConnectResponse, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SessionStore.get(context) }
    val scanApi = remember { ApiClient.scanApi(context) }
    val dao = remember { AppDatabase.get(context).pendingScanDao() }
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf(initialSession) }
    var online by remember { mutableStateOf(isOnline(context)) }
    var queued by remember { mutableStateOf(0) }
    var readerStatus by remember { mutableStateOf(ReaderStatus.CONNECTING) }
    var lastError by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ScanResponse?>(null) }
    var feed by remember { mutableStateOf<List<TodayFeedEntry>>(emptyList()) }
    val usbDisconnected = remember { AtomicBoolean(false) }
    var currentReader by remember { mutableStateOf<BiometricReader?>(null) }
    var showTable by remember { mutableStateOf(true) }
    var reportFormat by remember { mutableStateOf("pdf") }
    var downloading by remember { mutableStateOf(false) }

    var apiError by remember { mutableStateOf(false) }

    suspend fun refreshQueued() { queued = runCatching { dao.count() }.getOrDefault(0) }

    suspend fun refreshFeed() {
        runCatching {
            val response = scanApi.todayFeed(session.sessionToken)
            feed = response.entries ?: emptyList()
            apiError = false
            // Actualizar el nombre del restaurante si cambió
            val newName = response.restaurantName
            if (newName != null && newName != session.restaurantName) {
                val updatedSession = session.copy(restaurantName = newName)
                store.kioskSession = updatedSession
                session = updatedSession
            }
        }.onFailure {
            apiError = true
        }
    }

    // El ciclo de sincronización (LaunchedEffect(online) más abajo) se relanza cada vez que
    // cambia `online`, lo que puede solapar una syncQueue() nueva con una anterior que sigue
    // en vuelo (p. ej. WiFi inestable) y reenviar el mismo lote dos veces. Este guard evita
    // que dos ejecuciones se solapen.
    val syncing = remember { AtomicBoolean(false) }
    suspend fun syncQueue() {
        if (!syncing.compareAndSet(false, true)) return
        try {
            val items = dao.pending()
            if (items.isNotEmpty()) {
                try {
                    val records = items.map { ScanRequest(templateB64 = it.templateB64, mealTypeCode = it.mealTypeCode,
                        clientUuid = it.clientUuid, offline = true, consumedAt = it.consumedAt) }
                    val res = scanApi.sync(SyncBatchRequest(session.sessionToken, records))
                    res.results?.forEach { r -> if (r.status != "ERROR" && r.clientUuid != null) dao.remove(r.clientUuid) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
            refreshQueued()
            refreshFeed()
        } finally {
            syncing.set(false)
        }
    }

    suspend fun downloadReport() {
        if (downloading) return
        downloading = true
        try {
            val response = scanApi.exportToday(session.sessionToken, reportFormat)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val disposition = response.headers()["Content-Disposition"] ?: ""
                val filename = if (disposition.contains("filename=")) {
                    disposition.substringAfter("filename=\"").substringBefore("\"")
                } else {
                    val ext = when (reportFormat) {
                        "excel" -> "xlsx"
                        "csv" -> "csv"
                        else -> "pdf"
                    }
                    "reporte-diario-${LocalDate.now()}.$ext"
                }
                // body.bytes() hace una lectura de red bloqueante (la respuesta es @Streaming,
                // Retrofit no la buffer-iza de antemano): debe ir en IO o dispara
                // NetworkOnMainThreadException en el hilo de la corrutina (Main).
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { body.bytes() }
                if (bytes.isEmpty()) {
                    Toast.makeText(context, "El reporte está vacío", Toast.LENGTH_LONG).show()
                    return
                }

                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        saveFileToDownloads(context, filename, bytes)
                    } catch (e: Exception) {
                        Log.e("DownloadReport", "Error guardando en Descargas", e)
                        // Fallback a caché local si falla MediaStore
                        val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                        java.io.File(dir, filename).also { f ->
                            f.outputStream().use { out -> out.write(bytes) }
                        }
                    }
                }

                Toast.makeText(context, "Reporte guardado exitosamente: $filename", Toast.LENGTH_LONG).show()

                // Intentar abrir el archivo si hay un visor disponible
                runCatching {
                    val uri = if (file is java.io.File && file.exists()) {
                        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    } else null
                    
                    if (uri != null) {
                        val mime = when (reportFormat) {
                            "csv" -> "text/csv"
                            "excel" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            else -> "application/pdf"
                        }
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(viewIntent, "Abrir reporte"))
                    }
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                val errorMsg = when (response.code()) {
                    401 -> "Sesión inválida. Reconecte el dispositivo."
                    403 -> "No tiene permisos para generar reportes."
                    404 -> "No se encontraron registros para hoy."
                    in 500..599 -> "Error del servidor (${response.code()}). Intente más tarde."
                    else -> "Error al generar reporte (${response.code()})${if (errorBody.isNotEmpty()) ": $errorBody" else ""}"
                }
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            }
        } catch (e: java.net.UnknownHostException) {
            Toast.makeText(context, "Sin conexión al servidor", Toast.LENGTH_SHORT).show()
        } catch (e: java.net.SocketTimeoutException) {
            Toast.makeText(context, "Tiempo de espera agotado", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("DownloadReport", "Error al descargar reporte", e)
            Toast.makeText(context, "Error: ${e.message ?: "desconocido"}", Toast.LENGTH_LONG).show()
        } finally {
            downloading = false
        }
    }

    suspend fun processCapture(template: String) {
        val clientUuid = UUID.randomUUID().toString()
        // Se normaliza a UTC antes de guardar: la cola offline (PendingScan.pending())
        // ordena por este string con SQL "ORDER BY consumedAt ASC" (orden léxico), que solo
        // coincide con el orden cronológico real si todos los registros comparten el mismo
        // offset. Fijar UTC evita que un cambio de huso/horario de verano del dispositivo
        // reordene los registros pendientes al sincronizar.
        val consumedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()
        if (online) {
            try {
                result = scanApi.scan(ScanRequest(session.sessionToken, template, null, clientUuid, false, consumedAt))
                if (result?.status == "SUCCESS") {
                    ToneFeedback.success()
                    refreshFeed()
                } else {
                    ToneFeedback.error()
                }
                return
            } catch (e: retrofit2.HttpException) {
                val parsed = e.apiError()
                // Sesión de dispositivo inválida/expirada → forzar reconexión
                // (no encolar: el registro no podría sincronizarse tampoco).
                if (parsed?.code == "INVALID_SESSION") {
                    ToneFeedback.error()
                    store.kioskSession = null
                    onDisconnect()
                    return
                }
                // Otros 4xx del backend (INVALID_TEMPLATE, VALIDATION, etc.) son
                // errores reproducibles: mostrar el mensaje real antes que esconder
                // el problema en la cola offline.
                if (e.code() in 400..499) {
                    result = ScanResponse("ERROR", e.apiMessage("Error del servidor"), null, null, null, consumedAt)
                    ToneFeedback.error()
                    return
                }
                // 5xx u otros errores HTTP → degradar a offline (pueden ser transitorios)
            } catch (_: java.net.UnknownHostException) { /* sin red → offline */ }
              catch (_: java.net.ConnectException) { /* servidor caído → offline */ }
              catch (_: java.net.SocketTimeoutException) { /* timeout → offline */ }
              // IOException genérica: cubre SocketException ("Connection reset"), SSLException,
              // EOFException y otros cortes de red transitorios típicos de WiFi inestable en un
              // kiosco. Antes cualquiera de estos caía en el catch(Exception) de abajo y el
              // escaneo se perdía en vez de encolarse para reintento.
              catch (_: java.io.IOException) { /* red inestable → offline */ }
              catch (e: Exception) {
                // Cualquier otro fallo inesperado: mostrarlo en lugar de encolar a
                // ciegas, para no esconder bugs reales detrás de un "REGISTRO EN COLA".
                result = ScanResponse("ERROR", e.apiMessage("No se pudo validar la huella"), null, null, null, consumedAt)
                ToneFeedback.error()
                return
            }
        }
        dao.enqueue(PendingScan(clientUuid, template, null, consumedAt))
        refreshQueued()
        result = ScanResponse("QUEUED", "REGISTRO EN COLA (OFFLINE)", null, null, null, consumedAt)
        ToneFeedback.success()
    }

    // Feed de consumos del día (equivalente al feedPanel del Kiosk web)
    LaunchedEffect(Unit) {
        refreshFeed()
        while (isActive) {
            delay(10_000)
            refreshFeed()
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
        var consecutiveCaptureFailures = 0
        val maxFailuresBeforeNoDevice = 3
        
        while (isActive) {
            usbDisconnected.set(false)
            readerStatus = ReaderStatus.CONNECTING
            lastError = ""
            consecutiveCaptureFailures = 0
            
            val reader = BiometricReader.create(context)
            currentReader = reader
            // try/finally alrededor de TODO el ciclo de vida del reader: si la corrutina se
            // cancela (Activity destruida, "Cerrar Sesión de Restaurante") mientras está
            // suspendida dentro de reader.open()/capture(), CancellationException se propaga
            // sin pasar por los catch(Exception) de abajo — solo el finally garantiza que el
            // handle USB del ZK9500 se libere siempre, evitando que quede "abierto" para el
            // próximo intento de conexión.
            try {
                try {
                    reader.open { st ->
                        if (!usbDisconnected.get()) readerStatus = st
                    }
                } catch (e: Exception) {
                    currentReader = null
                    lastError = e.message ?: "Error desconocido"
                    if (readerStatus == ReaderStatus.CONNECTING) {
                        readerStatus = ReaderStatus.NO_DEVICE
                    }
                    delay(5_000); continue
                }

                while (isActive && !usbDisconnected.get()) {
                    try {
                        result = null
                        val template = reader.capture(20_000)
                        if (!usbDisconnected.get()) {
                            processCapture(template)
                            consecutiveCaptureFailures = 0
                        }
                        delay(1_000)
                        result = null
                    } catch (e: Exception) {
                        if (usbDisconnected.get()) break

                        consecutiveCaptureFailures++
                        lastError = e.message ?: "Error de captura"

                        if (consecutiveCaptureFailures >= maxFailuresBeforeNoDevice) {
                            readerStatus = ReaderStatus.NO_DEVICE
                            lastError = "El dispositivo no responde como lector ZK9500"
                            break
                        }

                        delay(800)
                        if (readerStatus == ReaderStatus.CONNECTING) readerStatus = ReaderStatus.ERROR
                    }
                }
            } finally {
                runCatching { reader.close() }
                currentReader = null
            }
        }
    }

    val ok = result?.status == "SUCCESS" || result?.status == "QUEUED"
    val bg = when {
        result == null -> Bg
        ok -> Success
        else -> ErrorRed
    }

    Surface(color = bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp)) {
            // ── Fila superior: Estado del Lector (Pill centralizada) ───────────────
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ReaderPill(readerStatus, Modifier)
            }

            Spacer(Modifier.height(40.dp))

            // ── Centro: Título de Restaurant y Animación/Estado ─────────────────────
            val r = result
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (r == null) {
                    Text(
                        session.restaurantName?.uppercase() ?: "RESTAURANTE",
                        style = MaterialTheme.typography.headlineMedium,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Light,
                        color = OnSurface
                    )
                    
                    Spacer(Modifier.height(30.dp))
                    
                    Image(
                        painter = painterResource(com.eatfood.control.mobile.R.drawable.ic_logo),
                        contentDescription = "EatFood",
                        modifier = Modifier
                            .size(140.dp)
                            .padding(vertical = 20.dp)
                            .alpha(if (readerStatus == ReaderStatus.READY) 1f else 0.25f)
                    )
                    
                    Text(
                        when (readerStatus) {
                            ReaderStatus.CONNECTING -> "Conectando lector…"
                            ReaderStatus.ERROR -> "Error: $lastError"
                            ReaderStatus.NO_DEVICE -> "Conecte el lector por USB-OTG"
                            ReaderStatus.DISCONNECTED -> "Reconectando…"
                            else -> "Coloque su dedo en el lector…"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        color = Primary
                    )
                    Text(
                        "Asegúrese de colocar la huella completa",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    // Pantalla de Resultado (Éxito o Error)
                    Text(
                        when (r.status) {
                            "SUCCESS" -> "✓ REGISTRO EXITOSO"
                            "QUEUED" -> "✓ REGISTRO EN COLA"
                            else -> "✕ ${r.message ?: "ERROR"}"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(20.dp))
                    r.employeeName?.let { 
                        Text(it.uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) 
                    }
                    r.mealName?.let { Text(it, color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp)) }
                    r.plates?.let { Text("$it Platos entregados", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp)) }
                    
                    if (r.status == "QUEUED") {
                        Spacer(Modifier.height(16.dp))
                        Text("Sincronización pendiente (sin conexión)", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                    }
                }
            }

            // ── Panel de registros de hoy (Diseño Tabla) ─────────────────────────
            TodayFeedPanel(
                feed = feed, online = online, apiError = apiError, queued = queued,
                showTable = showTable, onToggle = { showTable = !showTable },
                reportFormat = reportFormat,
                onFormatChange = { reportFormat = it },
                onDownload = { scope.launch { downloadReport() } },
                downloading = downloading,
                modifier = Modifier.heightIn(max = 320.dp)
            )
            
            Spacer(Modifier.height(16.dp))

            // ── Fila inferior ────────────────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = {
                    scope.launch { runCatching { scanApi.disconnect(session.sessionToken) } }
                    store.kioskSession = null
                    onDisconnect()
                }) { 
                    Text("Cerrar Sesión de Restaurante", color = Muted) 
                }
            }
        }
    }
}

@Composable
private fun ReaderPill(status: ReaderStatus, modifier: Modifier) {
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
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))
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

/** Panel lateral de consumos del día (espejo del diseño web). */
@Composable
private fun TodayFeedPanel(
    feed: List<TodayFeedEntry>,
    online: Boolean,
    apiError: Boolean,
    queued: Int,
    showTable: Boolean,
    onToggle: () -> Unit,
    reportFormat: String,
    onFormatChange: (String) -> Unit,
    onDownload: () -> Unit,
    downloading: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        // Título de sección con líneas (clickeable para colapsar/expandir)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onToggle() }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(Modifier.weight(1f), color = SurfaceVariant)
            Text(
                "  REGISTROS DE HOY ${if (showTable) "▲" else "▼"}  ",
                style = MaterialTheme.typography.labelLarge,
                color = Primary,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider(Modifier.weight(1f), color = SurfaceVariant)
        }

        Spacer(Modifier.height(12.dp))

        if (showTable) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface.copy(alpha = 0.5f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceVariant)
            ) {
                Column(Modifier.padding(8.dp)) {
                    // Header de Tabla
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text("#", Modifier.width(30.dp), color = Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("NOMBRE", Modifier.weight(1f), color = Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("HORA", Modifier.width(60.dp), color = Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Text("TIPO", Modifier.width(80.dp), color = Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.End)
                    }

                    HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

                    if (feed.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                            Text("No hay registros hoy", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        LazyColumn(Modifier.heightIn(max = 180.dp)) {
                            itemsIndexed(feed) { index, e ->
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${feed.size - index}", Modifier.width(30.dp), color = OnSurface, fontSize = 13.sp)
                                    Text(e.employeeName ?: "—", Modifier.weight(1f), color = OnSurface, fontSize = 13.sp, maxLines = 1)
                                    Text(
                                        e.time?.substringAfter('T')?.take(5) ?: "--:--",
                                        Modifier.width(60.dp), color = OnSurface, fontSize = 13.sp, textAlign = TextAlign.Center
                                    )
                                    Text(e.mealName ?: "Comida", Modifier.width(80.dp), color = OnSurface, fontSize = 13.sp, textAlign = TextAlign.End)
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

                    // Footer de Resumen
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total: ${feed.size}", color = OnSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (queued > 0) {
                            Text("Pendientes: $queued", color = Warning, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        val statusText = if (apiError) "○ Error servidor" else if (online) "● En línea" else "○ Offline"
                        val statusColor = if (apiError) ErrorRed else if (online) Success else Warning
                        Text(statusText, color = statusColor, fontSize = 12.sp)
                    }

                    HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

                    // Sección de descarga de reporte
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Selector de formato
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Formato:", color = OnSurface, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                Surface(
                                    modifier = Modifier.clickable { expanded = true },
                                    shape = RoundedCornerShape(4.dp),
                                    color = SurfaceVariant
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(reportFormat.uppercase(), color = OnSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(4.dp))
                                        Text("▼", color = OnSurface, fontSize = 10.sp)
                                    }
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    DropdownMenuItem(text = { Text("PDF") }, onClick = { onFormatChange("pdf"); expanded = false })
                                    DropdownMenuItem(text = { Text("Excel") }, onClick = { onFormatChange("excel"); expanded = false })
                                    DropdownMenuItem(text = { Text("CSV") }, onClick = { onFormatChange("csv"); expanded = false })
                                }
                            }
                        }

                        // Botón de descarga
                        Button(
                            onClick = onDownload,
                            enabled = !downloading,
                            colors = ButtonDefaults.buttonColors(containerColor = Success),
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                        ) {
                            if (downloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (downloading) "Generando..." else "📥 Descargar",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
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

private fun saveFileToDownloads(context: Context, filename: String, bytes: ByteArray) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, getMimeType(filename))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("No se pudo crear el archivo en Descargas")
        
        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(bytes)
        } ?: throw IOException("No se pudo abrir el archivo para escritura")
        
        contentValues.clear()
        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)
    } else {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val file = File(downloadsDir, filename)
        FileOutputStream(file).use { outputStream ->
            outputStream.write(bytes)
        }
    }
}

private fun getMimeType(filename: String): String {
    return when {
        filename.endsWith(".pdf") -> "application/pdf"
        filename.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        filename.endsWith(".csv") -> "text/csv"
        else -> "application/octet-stream"
    }
}
