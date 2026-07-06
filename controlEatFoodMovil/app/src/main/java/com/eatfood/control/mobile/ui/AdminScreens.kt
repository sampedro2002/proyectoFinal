package com.eatfood.control.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eatfood.control.mobile.biometric.BiometricReader
import com.eatfood.control.mobile.data.model.*
import com.eatfood.control.mobile.data.remote.ApiClient
import com.eatfood.control.mobile.data.remote.apiMessage
import kotlinx.coroutines.launch
import java.time.LocalDate

// ───────────────────────────── Dashboard ─────────────────────────────────────
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val api = remember { ApiClient.api(context) }
    var stats by remember { mutableStateOf<DashboardStats?>(null) }
    var trend by remember { mutableStateOf<List<TrendPoint>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            stats = api.dashboard()
            val to = LocalDate.now(); val from = to.minusDays(6)
            trend = runCatching { api.trend(from.toString(), to.toString()) }.getOrDefault(emptyList())
        } catch (e: Exception) { error = e.apiMessage("No se pudo cargar el panel") }
    }

    if (error != null) { CenterText(error!!); return }
    val s = stats ?: run { CenterText("Cargando…"); return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Estadísticas de hoy", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        val cards = listOf(
            "Consumos de hoy" to s.totalConsumptions.toString(),
            "Almuerzos" to s.lunchCount.toString(),
            "Meriendas" to s.snackCount.toString(),
            "Empleados esperados" to s.expectedEmployees.toString(),
            "Consumieron" to s.employeesConsumed.toString(),
            "Pendientes" to s.employeesPending.toString(),
            "% de consumo" to "${s.consumptionPercentage}%",
            "Huellas no reconocidas" to s.failedNotFound.toString(),
            "Fuera de horario" to s.failedOutOfSchedule.toString()
        )
        cards.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { (label, value) -> StatCard(label, value, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Tendencia (7 días)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                if (trend.isEmpty()) Text("Sin datos", color = MaterialTheme.colorScheme.onSurfaceVariant)
                trend.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(p.date ?: "", Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
                        Text("${p.records} registros", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun isValidTime(time: String): Boolean {
    val regex = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
    return regex.matches(time.trim())
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier.padding(6.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ───────────────────────────── Empleados ─────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeesScreen(canModify: Boolean) {
    val context = LocalContext.current
    val api = remember { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var fpEmployee by remember { mutableStateOf<EmployeeResponse?>(null) }
    if (fpEmployee != null) {
        FingerprintsScreen(fpEmployee!!, onBack = { fpEmployee = null }); return
    }

    var items by remember { mutableStateOf<List<EmployeeResponse>>(emptyList()) }
    var positions by remember { mutableStateOf<List<PositionResponse>>(emptyList()) }
    var term by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var editing by remember { mutableStateOf<EmployeeResponse?>(null) }
    var creating by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<EmployeeResponse?>(null) }

    suspend fun reload() {
        try { items = api.employees(term.ifBlank { null }, 0, 100).content ?: emptyList() }
        catch (e: Exception) { snackbar.showSnackbar(e.apiMessage()) }
    }
    LaunchedEffect(Unit) { positions = runCatching { api.positions() }.getOrDefault(emptyList()); reload() }

    val filtered = remember(items, statusFilter) {
        if (statusFilter == "ALL") items
        else items.filter { it.status == statusFilter }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canModify) FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, "Nuevo")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = term, onValueChange = { term = it },
                label = { Text("Buscar…") }, singleLine = true,
                trailingIcon = { TextButton(onClick = { scope.launch { reload() } }) { Text("Buscar") } },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
            
            var statusMenu by remember { mutableStateOf(false) }
            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                OutlinedButton(
                    onClick = { statusMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when (statusFilter) {
                            "ACTIVE" -> "Activos"
                            "INACTIVE" -> "Inactivos"
                            else -> "Todos"
                        }
                    )
                }
                DropdownMenu(statusMenu, { statusMenu = false }) {
                    DropdownMenuItem(text = { Text("Todos") }, onClick = { statusFilter = "ALL"; statusMenu = false })
                    DropdownMenuItem(text = { Text("Activos") }, onClick = { statusFilter = "ACTIVE"; statusMenu = false })
                    DropdownMenuItem(text = { Text("Inactivos") }, onClick = { statusFilter = "INACTIVE"; statusMenu = false })
                }
            }
            
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered) { e ->
                    RowItem(
                        title = e.fullName,
                        subtitle = "CI ${e.identityCard} · ${e.positionName ?: "Sin cargo"} · ${e.fingerprintCount}/3 huellas",
                        trailing = "${e.status} · ${e.effectivePlates}🍽",
                        onClick = { actionsFor = e }
                    )
                }
            }
        }
    }

    // Menú de acciones por empleado
    actionsFor?.let { e ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(e.fullName) },
            text = {
                Column {
                    if (canModify) TextButton(onClick = { editing = e; actionsFor = null }) { Text("Editar") }
                    TextButton(onClick = { fpEmployee = e; actionsFor = null }) { Text("Huellas") }
                    if (canModify) TextButton(onClick = {
                        val emp = e; actionsFor = null
                        scope.launch {
                            runCatching { api.deleteEmployee(emp.id) }
                                .onSuccess { reload() }
                                .onFailure { snackbar.showSnackbar(it.apiMessage()) }
                        }
                    }) { Text("Inactivar", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { actionsFor = null }) { Text("Cerrar") } }
        )
    }

    if (creating || editing != null) {
        EmployeeDialog(
            existing = editing, positions = positions,
            onDismiss = { creating = false; editing = null },
            onSaved = { creating = false; editing = null; scope.launch { reload() } },
            api = api, snackbar = snackbar, scope = scope
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmployeeDialog(
    existing: EmployeeResponse?,
    positions: List<PositionResponse>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    api: com.eatfood.control.mobile.data.remote.ApiService,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var identity by remember { mutableStateOf(existing?.identityCard ?: "") }
    var fullName by remember { mutableStateOf(existing?.fullName ?: "") }
    var plates by remember { mutableStateOf(existing?.allowedPlates?.toString() ?: "") }
    var allowsLunch by remember { mutableStateOf(existing?.allowsLunch ?: true) }
    var allowsSnack by remember { mutableStateOf(existing?.effectiveSnack ?: false) }
    var inactive by remember { mutableStateOf(existing?.status == "INACTIVE") }
    var positionId by remember { mutableStateOf(existing?.positionId) }
    var posMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nuevo empleado" else "Editar empleado") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(identity, { identity = it }, label = { Text("Cédula") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fullName, { fullName = it }, label = { Text("Nombre completo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedTextField(
                        value = positions.firstOrNull { it.id == positionId }?.name ?: "— Sin cargo —",
                        onValueChange = {}, readOnly = true, label = { Text("Cargo") },
                        trailingIcon = { TextButton(onClick = { posMenu = true }) { Text("▼") } },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(expanded = posMenu, onDismissRequest = { posMenu = false }) {
                        DropdownMenuItem(text = { Text("— Sin cargo —") }, onClick = { positionId = null; posMenu = false })
                        positions.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { positionId = p.id; posMenu = false })
                        }
                    }
                }
                OutlinedTextField(plates, { plates = it }, label = { Text("Platos (vacío = usar cargo)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(allowsLunch, { allowsLunch = it }); Text("Almuerzo")
                    Spacer(Modifier.width(16.dp))
                    Switch(allowsSnack, { allowsSnack = it }); Text("Merienda")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(inactive, { inactive = it }); Text("Inactivo")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (identity.isBlank() || fullName.isBlank()) {
                    scope.launch { snackbar.showSnackbar("Cédula y nombre son obligatorios") }; return@TextButton
                }
                val req = EmployeeRequest(
                    identityCard = identity.trim(), fullName = fullName.trim(),
                    positionId = positionId, status = if (inactive) "INACTIVE" else "ACTIVE",
                    allowedPlates = plates.trim().toIntOrNull(),
                    allowsLunch = allowsLunch, allowsSnack = allowsSnack
                )
                scope.launch {
                    runCatching {
                        if (existing == null) api.createEmployee(req) else api.updateEmployee(existing.id, req)
                    }.onSuccess { onSaved() }.onFailure { snackbar.showSnackbar(it.apiMessage("Error al guardar")) }
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ───────────────────────────── Huellas ───────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FingerprintsScreen(employee: EmployeeResponse, onBack: () -> Unit) {
    val context = LocalContext.current
    val api = remember { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val fingers = listOf(
        "Pulgar derecho", "Índice derecho", "Medio derecho", "Anular derecho", "Meñique derecho",
        "Pulgar izquierdo", "Índice izquierdo", "Medio izquierdo", "Anular izquierdo", "Meñique izquierdo"
    )
    var fps by remember { mutableStateOf<List<FingerprintResponse>>(emptyList()) }
    var fingerIndex by remember { mutableStateOf(1) }
    var fingerMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("idle") }

    suspend fun reload() { fps = runCatching { api.fingerprints(employee.id) }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Huellas — ${employee.fullName}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Box {
                        OutlinedTextField(
                            value = fingers.getOrElse(fingerIndex) { "Dedo $fingerIndex" },
                            onValueChange = {}, readOnly = true, label = { Text("Dedo a registrar") },
                            trailingIcon = { TextButton(onClick = { fingerMenu = true }) { Text("▼") } },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(expanded = fingerMenu, onDismissRequest = { fingerMenu = false }) {
                            fingers.forEachIndexed { i, f ->
                                DropdownMenuItem(text = { Text(f) }, onClick = { fingerIndex = i; fingerMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = status == "idle" && fps.size < 3,
                        onClick = {
                            status = "capturing"
                            val reader = BiometricReader.create(context)
                            scope.launch {
                                try {
                                    reader.open { }
                                    val template = reader.capture()
                                    reader.close()
                                    api.enroll(EnrollRequest(employee.id, fingerIndex, template))
                                    snackbar.showSnackbar("Huella registrada correctamente.")
                                    reload()
                                } catch (e: Exception) {
                                    reader.close(); snackbar.showSnackbar(e.apiMessage("Error al registrar"))
                                } finally { status = "idle" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(when {
                            fps.size >= 3 -> "Máximo 3 huellas"
                            status == "capturing" -> "Coloque el dedo…"
                            else -> "Capturar huella (${fps.size}/3)"
                        })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Huellas registradas (${fps.size}/3)", fontWeight = FontWeight.Bold)
            LazyColumn {
                items(fps) { fp ->
                    RowItem(
                        title = fingers.getOrElse(fp.fingerIndex) { "Dedo ${fp.fingerIndex}" },
                        subtitle = "Registrada: ${fp.enrolledAt ?: "—"}",
                        trailing = "Eliminar",
                        onClick = {
                            scope.launch {
                                runCatching { api.deleteFingerprint(fp.id) }.onSuccess { reload() }
                                    .onFailure { snackbar.showSnackbar(it.apiMessage()) }
                            }
                        }
                    )
                }
            }
        }
    }
}

// ───────────────────────────── Cargos ────────────────────────────────────────
@Composable
fun PositionsScreen(isAdmin: Boolean) {
    val context = LocalContext.current
    val api = remember { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var items by remember { mutableStateOf<List<PositionResponse>>(emptyList()) }
    var editing by remember { mutableStateOf<PositionResponse?>(null) }
    var creating by remember { mutableStateOf(false) }

    suspend fun reload() { items = runCatching { api.positions() }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { if (isAdmin) FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null) } }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(items) { p ->
                RowItem(
                    title = p.name,
                    subtitle = "Platos: ${p.defaultPlates} · Merienda: ${if (p.allowsSnack) "Sí" else "No"}",
                    trailing = if (p.active) "Activo" else "Inactivo",
                    onClick = { if (isAdmin) editing = p }
                )
            }
        }
    }

    if (creating || editing != null) {
        val p = editing
        var name by remember { mutableStateOf(p?.name ?: "") }
        var plates by remember { mutableStateOf((p?.defaultPlates ?: 1).toString()) }
        var snack by remember { mutableStateOf(p?.allowsSnack ?: false) }
        var active by remember { mutableStateOf(p?.active ?: true) }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (p == null) "Nuevo cargo" else "Editar cargo") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                    OutlinedTextField(plates, { plates = it }, label = { Text("Platos por defecto") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(snack, { snack = it }); Text("Permite merienda") }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(active, { active = it }); Text("Activo") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val req = PositionRequest(name.trim(), plates.toIntOrNull() ?: 0, snack, active)
                    scope.launch {
                        runCatching { if (p == null) api.createPosition(req) else api.updatePosition(p.id, req) }
                            .onSuccess { creating = false; editing = null; reload() }
                            .onFailure { snackbar.showSnackbar(it.apiMessage("Error al guardar")) }
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancelar") } }
        )
    }
}

// ───────────────────────────── Caterings ─────────────────────────────────────
@Composable
fun CateringsScreen(isAdmin: Boolean) {
    val context = LocalContext.current
    val api = remember { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var items by remember { mutableStateOf<List<CateringResponse>>(emptyList()) }
    var editing by remember { mutableStateOf<CateringResponse?>(null) }
    var creating by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<CateringResponse?>(null) }

    suspend fun reload() { items = runCatching { api.caterings() }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { if (isAdmin) FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null) } }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(items) { c ->
                RowItem(
                    title = c.name,
                    subtitle = "${c.location ?: "Sin ubicación"} · Conectados ${c.connectedDevices}/${c.maxDevices}",
                    trailing = if (c.active) "Activo" else "Inactivo",
                    onClick = { actionsFor = c }
                )
            }
        }
    }

    actionsFor?.let { c ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(c.name) },
            text = {
                Column {
                    Text("Ubicación: ${c.location ?: "Sin ubicación"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Dispositivos: ${c.connectedDevices}/${c.maxDevices}", style = MaterialTheme.typography.bodyMedium)
                    Text("Estado: ${if (c.active) "Activo" else "Inactivo"}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    if (isAdmin) {
                        TextButton(onClick = { editing = c; actionsFor = null }) { Text("Editar") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionsFor = null }) { Text("Cerrar") } }
        )
    }

    if (creating || editing != null) {
        val c = editing
        var name by remember { mutableStateOf(c?.name ?: "") }
        var location by remember { mutableStateOf(c?.location ?: "") }
        var maxDevices by remember { mutableStateOf((c?.maxDevices ?: 2).toString()) }
        var active by remember { mutableStateOf(c?.active ?: true) }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (c == null) "Nuevo catering" else "Editar catering") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                    OutlinedTextField(location, { location = it }, label = { Text("Ubicación") }, singleLine = true)
                    OutlinedTextField(maxDevices, { maxDevices = it }, label = { Text("Máx. dispositivos") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(active, { active = it }); Text("Activo") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val req = CateringRequest(name.trim(), location.trim().ifBlank { null }, active, maxDevices.toIntOrNull() ?: 2)
                    scope.launch {
                        runCatching { if (c == null) api.createCatering(req) else api.updateCatering(c.id, req) }
                            .onSuccess { creating = false; editing = null; reload() }
                            .onFailure { snackbar.showSnackbar(it.apiMessage("Error al guardar")) }
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancelar") } }
        )
    }
}

// ───────────────────────────── Horarios ──────────────────────────────────────
@Composable
fun SchedulesScreen() {
    val context = LocalContext.current
    val api = remember { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var meals by remember { mutableStateOf<List<MealTypeResponse>>(emptyList()) }
    var schedules by remember { mutableStateOf<List<ScheduleResponse>>(emptyList()) }
    var editing by remember { mutableStateOf<MealTypeResponse?>(null) }

    suspend fun reload() {
        schedules = runCatching { api.schedules() }.getOrDefault(emptyList())
        meals = runCatching { api.mealTypes() }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(meals) { m ->
                val sc = schedules.firstOrNull { it.mealTypeId == m.id }
                RowItem(
                    title = m.name,
                    subtitle = if (sc?.startTime != null) "Horario: ${sc.startTime.take(5)} – ${sc.endTime?.take(5)}" else "Sin horario",
                    trailing = "Editar",
                    onClick = { editing = m }
                )
            }
        }
    }

    editing?.let { m ->
        val sc = schedules.firstOrNull { it.mealTypeId == m.id }
        var start by remember { mutableStateOf(sc?.startTime?.take(5) ?: "12:00") }
        var end by remember { mutableStateOf(sc?.endTime?.take(5) ?: "13:00") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Horario — ${m.name}") },
            text = {
                Column {
                    OutlinedTextField(start, { start = it }, label = { Text("Inicio (HH:mm)") }, singleLine = true)
                    OutlinedTextField(end, { end = it }, label = { Text("Fin (HH:mm)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!isValidTime(start) || !isValidTime(end)) {
                        scope.launch { snackbar.showSnackbar("Formato de hora inválido. Use HH:mm (ej: 12:00)") }
                        return@TextButton
                    }
                    scope.launch {
                        runCatching { api.saveSchedule(ScheduleRequest(m.id, start.trim(), end.trim(), true)) }
                            .onSuccess { editing = null; reload() }
                            .onFailure { snackbar.showSnackbar(it.apiMessage("Error al guardar")) }
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } }
        )
    }
}

// ───────────────────────────── Almuerzos Extra ───────────────────────────────
@Composable
fun ExtraMealsScreen() {
    val context = LocalContext.current
    val api = remember { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Catálogos cargados al inicio: caterings y tipos de comida
    var caterings by remember { mutableStateOf<List<CateringResponse>>(emptyList()) }
    var mealTypes by remember { mutableStateOf<List<MealTypeResponse>>(emptyList()) }
    var selectedCateringId by remember { mutableStateOf<Long?>(null) }

    // Búsqueda de empleado existente
    var employees by remember { mutableStateOf<List<EmployeeResponse>>(emptyList()) }
    var term by remember { mutableStateOf("") }
    var selectedEmployee by remember { mutableStateOf<EmployeeResponse?>(null) }

    // Persona externa
    var isExternal by remember { mutableStateOf(false) }
    var extName by remember { mutableStateOf("") }
    var extCard by remember { mutableStateOf("") }

    // Comidas a registrar (LUNCH / SNACK)
    var almuerzo by remember { mutableStateOf(false) }
    var merienda by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var isEditing by remember { mutableStateOf(false) }

    // Carga inicial de caterings y meal types
    LaunchedEffect(Unit) {
        runCatching { api.caterings() }.onSuccess { list ->
            caterings = list
            if (selectedCateringId == null && list.isNotEmpty()) selectedCateringId = list.first().id
        }
        runCatching { api.mealTypes() }.onSuccess { mealTypes = it }
    }

    suspend fun searchEmployees() {
        if (term.isBlank()) { employees = emptyList(); return }
        employees = runCatching { api.employees(term, 0, 20).content ?: emptyList() }.getOrDefault(emptyList())
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Gestión de Almuerzos Extra", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            if (!isEditing) {
                // Selector de catering (el backend lo exige @NotNull)
                Text("Catering:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Box {
                    var catMenu by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = caterings.firstOrNull { it.id == selectedCateringId }?.name
                            ?: "Seleccione catering",
                        onValueChange = {},
                        readOnly = true,
                        enabled = caterings.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().clickable { catMenu = true }
                    )
                    DropdownMenu(catMenu, { catMenu = false }) {
                        caterings.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                onClick = { selectedCateringId = c.id; catMenu = false }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = term, onValueChange = { term = it },
                    label = { Text("Buscar empleado existente") }, singleLine = true,
                    trailingIcon = { IconButton(onClick = { scope.launch { searchEmployees() } }) { Icon(Icons.Default.Search, null) } },
                    modifier = Modifier.fillMaxWidth()
                )

                if (employees.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            employees.forEach { e ->
                                RowItem(
                                    title = e.fullName,
                                    subtitle = "CI ${e.identityCard} · ${e.positionName ?: "Sin cargo"}",
                                    onClick = {
                                        selectedEmployee = e
                                        isExternal = false
                                        isEditing = true
                                        employees = emptyList()
                                        term = ""
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        selectedEmployee = null
                        extName = ""
                        extCard = ""
                        isExternal = true
                        isEditing = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Agregar persona externa")
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (isExternal) "Nueva Persona Externa" else "Registrar para Empleado",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))

                        // Catering seleccionado (sólo lectura en modo edición)
                        Text(
                            "Catering: ${caterings.firstOrNull { it.id == selectedCateringId }?.name ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))

                        if (isExternal) {
                            OutlinedTextField(
                                value = extName, onValueChange = { extName = it },
                                label = { Text("Nombre completo") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = extCard, onValueChange = { extCard = it },
                                label = { Text("Cédula / ID") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // Empleado seleccionado: mostrar datos (sólo lectura)
                            OutlinedTextField(
                                value = selectedEmployee?.fullName ?: "",
                                onValueChange = {},
                                label = { Text("Nombre") },
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = selectedEmployee?.identityCard ?: "",
                                onValueChange = {},
                                label = { Text("Cédula") },
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("Servicios a registrar:", style = MaterialTheme.typography.labelLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(almuerzo, { almuerzo = it })
                            Text("Almuerzo", Modifier.clickable { almuerzo = !almuerzo })
                            Spacer(Modifier.width(20.dp))
                            Checkbox(merienda, { merienda = it })
                            Text("Merienda", Modifier.clickable { merienda = !merienda })
                        }

                        Spacer(Modifier.height(20.dp))
                        val canSubmit = !busy && (almuerzo || merienda) &&
                            selectedCateringId != null &&
                            if (isExternal) extName.isNotBlank() && extCard.isNotBlank()
                            else selectedEmployee != null

                        Button(
                            enabled = canSubmit,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val codes = mutableListOf<String>()
                                    if (almuerzo) codes.add("LUNCH")
                                    if (merienda) codes.add("SNACK")
                                    val res = mutableListOf<String>()
                                    val cateringId = selectedCateringId!!
                                    for (code in codes) {
                                        if (isExternal) {
                                            runCatching {
                                                api.manualScanExternal(
                                                    ExternalScanRequest(extCard.trim(), extName.trim(), code, cateringId)
                                                )
                                            }.onSuccess { r -> res.add("${r.mealName ?: code}: ${r.message ?: r.status}") }
                                             .onFailure { e -> res.add("$code: ${e.apiMessage("Error")}") }
                                        } else {
                                            runCatching {
                                                api.manualScan(
                                                    ManualScanRequest(selectedEmployee!!.id, code, cateringId)
                                                )
                                            }.onSuccess { r -> res.add("${r.mealName ?: code}: ${r.message ?: r.status}") }
                                             .onFailure { e -> res.add("$code: ${e.apiMessage("Error")}") }
                                        }
                                    }
                                    results = res
                                    if (res.none { it.contains("Error") }) {
                                        snackbar.showSnackbar("Registros guardados con éxito")
                                        isEditing = false
                                        almuerzo = false; merienda = false
                                        selectedEmployee = null
                                        extName = ""; extCard = ""
                                    }
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "Guardando…" else "Confirmar Registro") }

                        TextButton(
                            onClick = {
                                isEditing = false
                                results = emptyList()
                                selectedEmployee = null
                                extName = ""; extCard = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Cancelar") }
                    }
                }
            }

            if (results.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                results.forEach { r ->
                    Text(
                        r,
                        color = if (r.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
