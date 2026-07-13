package com.eatfood.control.mobile.ui

import android.app.DatePickerDialog
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.eatfood.control.mobile.data.model.RestaurantResponse
import com.eatfood.control.mobile.data.model.ConsumptionRow
import com.eatfood.control.mobile.data.model.MealTypeResponse
import com.eatfood.control.mobile.data.remote.ApiClient
import com.eatfood.control.mobile.data.remote.apiMessage
import com.eatfood.control.mobile.data.prefs.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

// ───────────────────────────── Reportes ──────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen() {
    val context = LocalContext.current
    val api = remember(SessionStore.get(context).serverUrl) { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var from by remember { mutableStateOf(LocalDate.now()) }
    var to by remember { mutableStateOf(LocalDate.now()) }
    var restaurants by remember { mutableStateOf<List<RestaurantResponse>>(emptyList()) }
    var meals by remember { mutableStateOf<List<MealTypeResponse>>(emptyList()) }
    var restaurantId by remember { mutableStateOf<Long?>(null) }
    var mealTypeId by remember { mutableStateOf<Long?>(null) }
    var rows by remember { mutableStateOf<List<ConsumptionRow>>(emptyList()) }
    var catMenu by remember { mutableStateOf(false) }
    var mealMenu by remember { mutableStateOf(false) }

    suspend fun search() {
        runCatching { api.consumptions(from.toString(), to.toString(), restaurantId, mealTypeId) }
            .onSuccess { rows = it }
            .onFailure { snackbar.showSnackbar(it.apiMessage()) }
    }
    fun exportFile(format: String) {
        scope.launch {
            try {
                val res = api.export(format, from.toString(), to.toString(), restaurantId, mealTypeId)
                val body = res.body() ?: error("Respuesta vacía")
                val ext = if (format == "excel") "xlsx" else format
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    File(dir, "consumos.$ext").also { f ->
                        f.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val mime = when (format) {
                    "csv" -> "text/csv"; "pdf" -> "application/pdf"
                    else -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                }
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(view, "Abrir reporte"))
            } catch (e: Exception) { snackbar.showSnackbar(e.apiMessage("No se pudo exportar")) }
        }
    }

    LaunchedEffect(Unit) {
        restaurants = runCatching { api.restaurants() }.getOrDefault(emptyList())
        meals = runCatching { api.mealTypes() }.getOrDefault(emptyList())
        search()
    }

    fun pickDate(initial: LocalDate, onPick: (LocalDate) -> Unit) {
        DatePickerDialog(context, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        OutlinedButton(onClick = { pickDate(from) { from = it } }, modifier = Modifier.weight(1f)) { Text("Desde: $from") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { pickDate(to) { to = it } }, modifier = Modifier.weight(1f)) { Text("Hasta: $to") }
                    }
                    Box {
                        OutlinedButton(onClick = { catMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(restaurants.firstOrNull { it.id == restaurantId }?.name ?: "Todos los restaurants")
                        }
                        DropdownMenu(catMenu, { catMenu = false }) {
                            DropdownMenuItem(text = { Text("Todos los restaurants") }, onClick = { restaurantId = null; catMenu = false })
                            restaurants.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { restaurantId = c.id; catMenu = false }) }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { mealMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(meals.firstOrNull { it.id == mealTypeId }?.name ?: "Todas las comidas")
                        }
                        DropdownMenu(mealMenu, { mealMenu = false }) {
                            DropdownMenuItem(text = { Text("Todas las comidas") }, onClick = { mealTypeId = null; mealMenu = false })
                            meals.forEach { m -> DropdownMenuItem(text = { Text(m.name) }, onClick = { mealTypeId = m.id; mealMenu = false }) }
                        }
                    }
                    Button(onClick = { scope.launch { search() } }, modifier = Modifier.fillMaxWidth()) { Text("Consultar") }
                    Row {
                        TextButton(onClick = { exportFile("csv") }, modifier = Modifier.weight(1f)) { Text("CSV") }
                        TextButton(onClick = { exportFile("excel") }, modifier = Modifier.weight(1f)) { Text("Excel") }
                        TextButton(onClick = { exportFile("pdf") }, modifier = Modifier.weight(1f)) { Text("PDF") }
                    }
                }
            }
            Text(
                "${rows.size} registros",
                Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows) { r ->
                    RowItem(
                        title = "${r.employeeName ?: "—"} · ${r.mealName ?: ""}",
                        subtitle = "${r.businessDate ?: ""} ${timeOf(r.consumedAt)} · CI ${r.identityCard ?: "—"} · ${r.restaurantName ?: ""}",
                        trailing = if (r.offline) "offline" else ""
                    )
                }
            }
        }
    }
}

private fun timeOf(iso: String?): String =
    iso?.let { runCatching { it.substringAfter('T').take(8) }.getOrNull() } ?: ""

// ───────────────────────────── Auditoría ─────────────────────────────────────
@Composable
fun AuditScreen() {
    val context = LocalContext.current
    val api = remember(SessionStore.get(context).serverUrl) { ApiClient.api(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var entity by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<com.eatfood.control.mobile.data.model.AuditRow>>(emptyList()) }

    suspend fun reload() {
        runCatching { api.audit(entity.ifBlank { null }, 0, 100).content ?: emptyList() }
            .onSuccess { rows = it }.onFailure { snackbar.showSnackbar(it.apiMessage()) }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = entity, onValueChange = { entity = it },
                label = { Text("Filtrar por entidad…") }, singleLine = true,
                trailingIcon = { TextButton(onClick = { scope.launch { reload() } }) { Text("Filtrar") } },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows) { r ->
                    RowItem(
                        title = "${r.action ?: ""} · ${r.entityName ?: ""} #${r.entityId ?: ""}",
                        subtitle = "${r.username ?: "—"} · ${r.createdAt ?: ""}" + (r.ipAddress?.let { " · $it" } ?: "")
                    )
                }
            }
        }
    }
}
