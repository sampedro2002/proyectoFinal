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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.eatfood.control.mobile.biometric.BiometricReader
import com.eatfood.control.mobile.data.model.*
import com.eatfood.control.mobile.data.remote.ApiClient
import com.eatfood.control.mobile.data.remote.apiMessage
import com.eatfood.control.mobile.data.prefs.SessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

// ───────────────────────────── Dashboard ─────────────────────────────────────
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val api = remember(SessionStore.get(context).serverUrl) { ApiClient.api(context) }
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
            "Almuerzos" to s.almuerzoCount.toString(),
            "Meriendas" to s.meriendaCount.toString(),
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
    val api = remember(SessionStore.get(context).serverUrl) { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var fpEmployee by remember { mutableStateOf<EmployeeResponse?>(null) }
    if (fpEmployee != null) {
        FingerprintsScreen(fpEmployee!!, onBack = { fpEmployee = null }); return
    }

    var items by remember { mutableStateOf<List<EmployeeResponse>>(emptyList()) }
    var term by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var editing by remember { mutableStateOf<EmployeeResponse?>(null) }
    var creating by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<EmployeeResponse?>(null) }

    suspend fun reload() {
        try { items = api.employees(term.ifBlank { null }, 0, 100).content ?: emptyList() }
        catch (e: Exception) { snackbar.showSnackbar(e.apiMessage()) }
    }
    LaunchedEffect(Unit) { reload() }

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
                        subtitle = "CI ${e.identityCard} · ${e.fingerprintCount}/3 huellas",
                        trailing = e.status ?: "",
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
                        // No existe DELETE /api/employees en el backend: "inactivar" es un
                        // update con status=INACTIVE, igual que en la web.
                        scope.launch {
                            runCatching {
                                api.updateEmployee(emp.id, EmployeeRequest(
                                    identityCard = emp.identityCard,
                                    fullName = emp.fullName,
                                    observation = emp.observation,
                                    isPassport = !com.eatfood.control.mobile.util.CedulaValidator.isValid(emp.identityCard),
                                    status = "INACTIVE",
                                    allowsLunch = emp.allowsLunch,
                                    allowsSnack = emp.allowsSnack
                                ))
                            }
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
            existing = editing,
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
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    api: com.eatfood.control.mobile.data.remote.ApiService,
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var identity by remember { mutableStateOf(existing?.identityCard ?: "") }
    var fullName by remember { mutableStateOf(existing?.fullName ?: "") }
    var observation by remember { mutableStateOf(existing?.observation ?: "") }
    var allowsLunch by remember { mutableStateOf(existing?.allowsLunch ?: true) }
    var allowsSnack by remember { mutableStateOf(existing?.allowsSnack ?: existing?.effectiveSnack ?: false) }
    var inactive by remember { mutableStateOf(existing?.status == "INACTIVE") }
    var isPassport by remember { mutableStateOf(existing?.identityCard?.let { !com.eatfood.control.mobile.util.CedulaValidator.isValid(it) } ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nuevo empleado" else "Editar empleado") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(isPassport, { isPassport = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Es Pasaporte")
                }
                OutlinedTextField(identity, { identity = it }, label = { Text(if (isPassport) "Pasaporte" else "Cédula") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fullName, { fullName = it }, label = { Text("Nombre completo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(allowsLunch, { allowsLunch = it }); Text("Almuerzo")
                    Spacer(Modifier.width(16.dp))
                    Switch(allowsSnack, { allowsSnack = it }); Text("Merienda")
                }
                OutlinedTextField(observation, { observation = it }, label = { Text("Observación (opcional)") },
                    modifier = Modifier.fillMaxWidth())
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
                if (!isPassport && !com.eatfood.control.mobile.util.CedulaValidator.isValid(identity.trim())) {
                    scope.launch { snackbar.showSnackbar("La cédula no es una cédula ecuatoriana válida (10 dígitos con verificador)") }
                    return@TextButton
                }
                val req = EmployeeRequest(
                    identityCard = identity.trim(), fullName = fullName.trim(),
                    observation = observation.trim().ifBlank { null },
                    isPassport = isPassport,
                    status = if (inactive) "INACTIVE" else "ACTIVE",
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
    val api = remember(SessionStore.get(context).serverUrl) { ApiClient.api(context) }
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
    // Muestras ya capturadas del enrolamiento (0..3); -1 = aún inicializando el lector.
    var sample by remember { mutableStateOf(-1) }
    var openMsg by remember { mutableStateOf("Iniciando lector…") }

    suspend fun reload() { fps = runCatching { api.fingerprints(employee.id) }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { reload() }

    // Auto-seleccionar el siguiente dedo disponible si el actual ya está registrado
    // (mismo comportamiento que la web en Employees.jsx).
    LaunchedEffect(fps) {
        val used = fps.map { it.fingerIndex }.toSet()
        if (fingerIndex in used) {
            fingers.indices.firstOrNull { it !in used }?.let { fingerIndex = it }
        }
    }

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
                            val used = fps.map { it.fingerIndex }.toSet()
                            fingers.forEachIndexed { i, f ->
                                val registered = i in used
                                DropdownMenuItem(
                                    text = { Text(if (registered) "$f (Registrado)" else f) },
                                    enabled = !registered,
                                    onClick = { fingerIndex = i; fingerMenu = false }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = status == "idle" && fps.size < 3,
                        onClick = {
                            status = "capturing"; sample = -1; openMsg = "Iniciando lector…"
                            scope.launch {
                                var reader: BiometricReader? = null
                                try {
                                    // El primer open() tras enchufar el lector es inestable
                                    // (diálogo de permiso USB en trámite, inicialización del
                                    // SDK ZKFinger): el kiosco lo absorbe reintentando en
                                    // bucle cada 5 s; aquí replicamos esa resiliencia con
                                    // varios intentos en vez de rendirnos al primero.
                                    var lastError: Exception? = null
                                    for (attempt in 1..3) {
                                        openMsg = if (attempt == 1) "Iniciando lector…"
                                                  else "Reintentando conexión ($attempt/3)…"
                                        val r = BiometricReader.create(context)
                                        try {
                                            r.open { }
                                            reader = r
                                            break
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            r.close(); throw e
                                        } catch (e: Exception) {
                                            r.close()
                                            lastError = e
                                            if (attempt < 3) delay(2_000)
                                        }
                                    }
                                    val rdr = reader
                                        ?: throw (lastError ?: Exception("No se pudo abrir el lector"))
                                    // 3 muestras del mismo dedo fusionadas en una plantilla,
                                    // igual que el enrolamiento de la web.
                                    val template = rdr.captureForEnroll { s, _ -> sample = s }
                                    rdr.close()
                                    api.enroll(EnrollRequest(employee.id, fingerIndex, template))
                                    snackbar.showSnackbar("Huella registrada correctamente.")
                                    reload()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    snackbar.showSnackbar(e.apiMessage("Error al registrar"))
                                } finally {
                                    runCatching { reader?.close() }
                                    status = "idle"; sample = -1
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(when {
                            fps.size >= 3 -> "Máximo 3 huellas"
                            status == "capturing" && sample < 0 -> openMsg
                            status == "capturing" && sample == 0 -> "Coloque el dedo… (0/3)"
                            status == "capturing" && sample >= 3 -> "Guardando… (3/3)"
                            status == "capturing" -> "Muestra $sample/3 registrada — levante y coloque de nuevo"
                            else -> "Capturar huella (${fps.size}/3)"
                        })
                    }
                    if (status == "capturing") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Se tomarán 3 muestras del mismo dedo. Levante el dedo entre cada una.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

// ───────────────────────────── Restaurants ─────────────────────────────────────
@Composable
fun RestaurantsScreen(isAdmin: Boolean) {
    val context = LocalContext.current
    val api = remember(SessionStore.get(context).serverUrl) { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var items by remember { mutableStateOf<List<RestaurantResponse>>(emptyList()) }
    var editing by remember { mutableStateOf<RestaurantResponse?>(null) }
    var creating by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<RestaurantResponse?>(null) }

    suspend fun reload() { items = runCatching { api.restaurants() }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { if (isAdmin) FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null) } }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(items) { c ->
                RowItem(
                    title = c.name,
                    subtitle = "${c.location ?: "Sin ubicación"} · Resp.: ${c.representative ?: "—"} · ${c.connectedDevices}/${c.maxDevices}",
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
                    Text("Responsable: ${c.representative ?: "—"}", style = MaterialTheme.typography.bodyMedium)
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
        var representative by remember { mutableStateOf(c?.representative ?: "") }
        var maxDevices by remember { mutableStateOf((c?.maxDevices ?: 2).toString()) }
        var active by remember { mutableStateOf(c?.active ?: true) }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (c == null) "Nuevo restaurante" else "Editar restaurante") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                    OutlinedTextField(location, { location = it }, label = { Text("Ubicación") }, singleLine = true)
                    OutlinedTextField(representative, { representative = it }, label = { Text("Responsable / Representante") }, singleLine = true)
                    OutlinedTextField(maxDevices, { maxDevices = it }, label = { Text("Máx. dispositivos") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(active, { active = it }); Text("Activo") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val req = RestaurantRequest(name.trim(), location.trim().ifBlank { null }, representative.trim().ifBlank { null }, active, maxDevices.toIntOrNull() ?: 2)
                    scope.launch {
                        runCatching { if (c == null) api.createRestaurant(req) else api.updateRestaurant(c.id, req) }
                            .onSuccess { creating = false; editing = null; reload() }
                            .onFailure { snackbar.showSnackbar(it.apiMessage("Error al guardar")) }
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text("Cancelar") } }
        )
    }
}

// ───────────────────────────── Usuarios ──────────────────────────────────────
// Réplica de la página Usuarios de la web (Users.jsx): lista, crear, editar,
// activar/desactivar, roles y asignación de restaurante. Mismas validaciones.

/** Etiquetas amigables para los roles internos (igual que ROLE_LABELS de la web). */
private fun roleLabel(name: String) = when (name) {
    "ADMIN" -> "Administrador"
    "CATERING" -> "Restaurante"
    else -> name
}

@Composable
fun UsersScreen() {
    val context = LocalContext.current
    val api = remember(SessionStore.get(context).serverUrl) { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var items by remember { mutableStateOf<List<UserResponse>>(emptyList()) }
    var roleNames by remember { mutableStateOf(listOf("ADMIN", "CATERING")) }
    var restaurants by remember { mutableStateOf<List<RestaurantResponse>>(emptyList()) }
    var editing by remember { mutableStateOf<UserResponse?>(null) }
    var creating by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<UserResponse?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        runCatching { api.users() }
            .onSuccess { items = it; loadError = null }
            .onFailure { items = emptyList(); loadError = it.apiMessage("No se pudieron cargar los usuarios") }
    }
    LaunchedEffect(Unit) {
        reload()
        runCatching { api.roles() }.onSuccess { r ->
            val names = r.mapNotNull { it.name }
            if (names.isNotEmpty()) roleNames = names
        }
        restaurants = runCatching { api.restaurants() }.getOrDefault(emptyList())
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null) } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            loadError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            if (items.isEmpty() && loadError == null) {
                CenterText("Sin usuarios.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items) { u ->
                        val rolesText = (u.roles ?: emptyList()).joinToString(", ") { roleLabel(it) }.ifBlank { "—" }
                        RowItem(
                            title = u.username,
                            subtitle = "${u.fullName} · $rolesText · ${u.restaurantName ?: "Sin restaurante"}",
                            trailing = if (u.enabled) "Activo" else "Inactivo",
                            onClick = { actionsFor = u }
                        )
                    }
                }
            }
        }
    }

    actionsFor?.let { u ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(u.username) },
            text = {
                Column {
                    Text("Nombre: ${u.fullName}", style = MaterialTheme.typography.bodyMedium)
                    Text("Email: ${u.email ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Roles: ${(u.roles ?: emptyList()).joinToString(", ") { roleLabel(it) }.ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Restaurante: ${u.restaurantName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Estado: ${if (u.enabled) "Activo" else "Inactivo"}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { editing = u; actionsFor = null }) { Text("Editar") }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { api.setUserEnabled(u.id, EnabledRequest(!u.enabled)) }
                                .onSuccess { actionsFor = null; reload() }
                                .onFailure { snackbar.showSnackbar(it.apiMessage("No se pudo cambiar el estado")) }
                        }
                    }) { Text(if (u.enabled) "Desactivar" else "Activar") }
                }
            },
            confirmButton = { TextButton(onClick = { actionsFor = null }) { Text("Cerrar") } }
        )
    }

    if (creating || editing != null) {
        val u = editing
        var username by remember { mutableStateOf(u?.username ?: "") }
        var fullName by remember { mutableStateOf(u?.fullName ?: "") }
        var email by remember { mutableStateOf(u?.email ?: "") }
        var password by remember { mutableStateOf("") }
        var password2 by remember { mutableStateOf("") }
        var showPass by remember { mutableStateOf(false) }
        var selRoles by remember { mutableStateOf(u?.roles?.takeIf { it.isNotEmpty() } ?: listOf("CATERING")) }
        var restaurantId by remember { mutableStateOf(u?.restaurantId) }
        var restaurantMenu by remember { mutableStateOf(false) }
        var enabled by remember { mutableStateOf(u?.enabled ?: true) }
        var formError by remember { mutableStateOf<String?>(null) }
        val passTransform = if (showPass) VisualTransformation.None else PasswordVisualTransformation()

        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (u == null) "Nuevo usuario" else "Editar usuario") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // El username identifica la cuenta: no se cambia al editar (igual que la web).
                    OutlinedTextField(username, { username = it }, label = { Text("Usuario") },
                        singleLine = true, enabled = u == null)
                    OutlinedTextField(fullName, { fullName = it }, label = { Text("Nombre completo") }, singleLine = true)
                    OutlinedTextField(email, { email = it }, label = { Text("Email (opcional)") }, singleLine = true)
                    OutlinedTextField(password, { password = it },
                        label = { Text(if (u == null) "Contraseña" else "Nueva contraseña (vacía = no cambiar)") },
                        singleLine = true, visualTransformation = passTransform,
                        trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Ocultar" else "Ver") } })
                    OutlinedTextField(password2, { password2 = it },
                        label = { Text("Confirmar contraseña") },
                        singleLine = true, visualTransformation = passTransform)
                    if (password2.isNotEmpty() && password != password2) {
                        Text("Las contraseñas no coinciden.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Roles", style = MaterialTheme.typography.titleSmall)
                    roleNames.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = name in selRoles, onCheckedChange = { checked ->
                                selRoles = if (checked) selRoles + name else selRoles - name
                            })
                            Text(roleLabel(name))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Box {
                        OutlinedTextField(
                            value = restaurants.firstOrNull { it.id == restaurantId }?.name ?: "— Ninguno —",
                            onValueChange = {}, readOnly = true,
                            label = { Text("Restaurante (para operadores)") },
                            trailingIcon = { TextButton(onClick = { restaurantMenu = true }) { Text("▼") } }
                        )
                        DropdownMenu(expanded = restaurantMenu, onDismissRequest = { restaurantMenu = false }) {
                            DropdownMenuItem(text = { Text("— Ninguno —") },
                                onClick = { restaurantId = null; restaurantMenu = false })
                            restaurants.forEach { r ->
                                DropdownMenuItem(text = { Text(r.name) },
                                    onClick = { restaurantId = r.id; restaurantMenu = false })
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(enabled, { enabled = it }); Text("Cuenta activa")
                    }
                    formError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Mismas validaciones que la web (Users.jsx: save()).
                    if (username.isBlank() || fullName.isBlank()) {
                        formError = "Usuario y nombre completo son obligatorios."; return@TextButton
                    }
                    if (u == null && password.isBlank()) {
                        formError = "La contraseña es obligatoria al crear."; return@TextButton
                    }
                    if (password.isNotEmpty() || password2.isNotEmpty()) {
                        if (password != password2) {
                            formError = "Las contraseñas no coinciden. Escriba la misma en ambos campos."; return@TextButton
                        }
                        if (password.length < 6) {
                            formError = "La contraseña debe tener al menos 6 caracteres."; return@TextButton
                        }
                    }
                    val req = UserRequest(
                        username.trim(), fullName.trim(), email.trim().ifBlank { null },
                        password.ifBlank { null }, enabled, selRoles, restaurantId
                    )
                    scope.launch {
                        runCatching { if (u == null) api.createUser(req) else api.updateUser(u.id, req) }
                            .onSuccess { creating = false; editing = null; reload() }
                            .onFailure { formError = it.apiMessage("Error al guardar") }
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
    val api = remember(SessionStore.get(context).serverUrl) { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var startTime by remember { mutableStateOf("12:00") }
    var endTime by remember { mutableStateOf("13:00") }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    suspend fun reload() {
        val schedules = runCatching { api.schedules() }.getOrDefault(emptyList())
        if (schedules.isNotEmpty()) {
            val first = schedules.first()
            startTime = first.startTime?.take(5) ?: "12:00"
            endTime = first.endTime?.take(5) ?: "13:00"
        }
        loaded = true
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Horario general", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            if (!loaded) {
                CenterText("Cargando…")
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Ventana de servicio",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Define el horario en que se permiten los registros de consumo (escaneo de huella).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = startTime,
                            onValueChange = { startTime = it },
                            label = { Text("Inicio (HH:mm)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = endTime,
                            onValueChange = { endTime = it },
                            label = { Text("Fin (HH:mm)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            enabled = !saving,
                            onClick = {
                                if (!isValidTime(startTime) || !isValidTime(endTime)) {
                                    scope.launch { snackbar.showSnackbar("Formato de hora inválido. Use HH:mm (ej: 12:00)") }
                                    return@Button
                                }
                                saving = true
                                scope.launch {
                                    runCatching {
                                        api.saveGeneralSchedule(
                                            GeneralScheduleRequest(startTime.trim(), endTime.trim(), true)
                                        )
                                    }.onSuccess {
                                        snackbar.showSnackbar("Horario guardado correctamente")
                                        reload()
                                    }.onFailure {
                                        snackbar.showSnackbar(it.apiMessage("Error al guardar horario"))
                                    }
                                    saving = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (saving) "Guardando…" else "Guardar") }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Platos Extra ─────────────────────────────────
@Composable
fun ExtraMealsScreen() {
    val context = LocalContext.current
    val api = remember(SessionStore.get(context).serverUrl) { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Catálogo cargado al inicio: restaurants
    var restaurants by remember { mutableStateOf<List<RestaurantResponse>>(emptyList()) }
    var selectedRestaurantId by remember { mutableStateOf<Long?>(null) }

    // Búsqueda de empleado existente
    var employees by remember { mutableStateOf<List<EmployeeResponse>>(emptyList()) }
    var term by remember { mutableStateOf("") }
    var selectedEmployee by remember { mutableStateOf<EmployeeResponse?>(null) }

    // Persona externa
    var isExternal by remember { mutableStateOf(false) }
    var extName by remember { mutableStateOf("") }
    var extCard by remember { mutableStateOf("") }
    var isPassport by remember { mutableStateOf(false) }

    // Comidas a registrar (BREAKFAST = Almuerzo / LUNCH = Merienda)
    var almuerzo by remember { mutableStateOf(false) }
    var merienda by remember { mutableStateOf(false) }
    var observation by remember { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var isEditing by remember { mutableStateOf(false) }

    // Carga inicial de restaurants
    LaunchedEffect(Unit) {
        runCatching { api.restaurants() }.onSuccess { list ->
            restaurants = list
            if (selectedRestaurantId == null && list.isNotEmpty()) selectedRestaurantId = list.first().id
        }
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
            Text("Gestión de Platos Extra", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            if (!isEditing) {
                // Selector de restaurant (el backend lo exige @NotNull)
                Text("Restaurante:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Box {
                    var catMenu by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = restaurants.firstOrNull { it.id == selectedRestaurantId }?.name
                            ?: "Seleccione restaurante",
                        onValueChange = {},
                        readOnly = true,
                        enabled = restaurants.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().clickable { catMenu = true }
                    )
                    DropdownMenu(catMenu, { catMenu = false }) {
                        restaurants.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                onClick = { selectedRestaurantId = c.id; catMenu = false }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = term, onValueChange = { term = it },
                    label = { Text("Buscar por identificación o nombre") }, singleLine = true,
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
                                    subtitle = "CI ${e.identityCard}",
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
                        isPassport = false
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

                        // Restaurant seleccionado (sólo lectura en modo edición)
                        Text(
                            "Restaurant: ${restaurants.firstOrNull { it.id == selectedRestaurantId }?.name ?: "—"}",
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(isPassport, { isPassport = it })
                                Spacer(Modifier.width(8.dp))
                                Text("Es Pasaporte")
                            }
                            OutlinedTextField(
                                value = extCard, onValueChange = { extCard = it },
                                label = { Text(if (isPassport) "Pasaporte" else "Cédula") },
                                singleLine = true,
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

                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = observation, onValueChange = { observation = it },
                            label = { Text("Observación (opcional)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(20.dp))
                        val canSubmit = !busy && (almuerzo || merienda) &&
                            selectedRestaurantId != null &&
                            if (isExternal) extName.isNotBlank() && extCard.isNotBlank()
                            else selectedEmployee != null

                        Button(
                            enabled = canSubmit,
                            onClick = {
                                val card = extCard.trim()
                                if (isExternal && !isPassport && !com.eatfood.control.mobile.util.CedulaValidator.isValid(card)) {
                                    scope.launch { snackbar.showSnackbar("La cédula no es válida (10 dígitos con verificador).") }
                                    return@Button
                                }
                                busy = true
                                scope.launch {
                                    val codes = mutableListOf<String>()
                                    if (almuerzo) codes.add("BREAKFAST")
                                    if (merienda) codes.add("LUNCH")
                                    val res = mutableListOf<String>()
                                    val restaurantId = selectedRestaurantId!!
                                    val obs = observation.trim().ifBlank { null }
                                    for (code in codes) {
                                        if (isExternal) {
                                            runCatching {
                                                api.manualScanExternal(
                                                    ExternalScanRequest(extCard.trim(), extName.trim(), code, restaurantId, obs, isPassport)
                                                )
                                            }.onSuccess { r -> res.add("${r.mealName ?: code}: ${r.message ?: r.status}") }
                                             .onFailure { e -> res.add("$code: ${e.apiMessage("Error")}") }
                                        } else {
                                            runCatching {
                                                api.manualScan(
                                                    ManualScanRequest(selectedEmployee!!.id, code, restaurantId, obs)
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
                                        extName = ""; extCard = ""; observation = ""
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
