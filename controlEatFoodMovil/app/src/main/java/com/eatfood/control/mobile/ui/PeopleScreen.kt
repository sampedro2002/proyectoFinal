package com.eatfood.control.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eatfood.control.mobile.data.model.*
import com.eatfood.control.mobile.data.remote.ApiClient
import com.eatfood.control.mobile.data.remote.apiMessage
import com.eatfood.control.mobile.util.CedulaValidator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(canModify: Boolean) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val tabs = if (canModify) listOf("Personal", "Personas externas", "Tipo de persona")
        else listOf("Personal", "Personas externas")
    val current = selected.coerceIn(tabs.indices)
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = current, edgePadding = 0.dp) {
            tabs.forEachIndexed { index, label ->
                Tab(selected = current == index, onClick = { selected = index }, text = { Text(label) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (current) {
                0 -> EmployeesScreen(canModify)
                1 -> ExternalPeopleScreen(canModify)
                2 -> PersonTypesScreen()
            }
        }
    }
}

@Composable
private fun ExternalPeopleScreen(canModify: Boolean) {
    val api = ApiClient.api(LocalContext.current)
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<ExternalPersonResponse>>(emptyList()) }
    var term by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ExternalPersonResponse?>(null) }
    var creating by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(term, revision) {
        loading = true
        error = null
        delay(350)
        try { people = api.externalPersons(term.ifBlank { null }).content.orEmpty() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.apiMessage() }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(value = term, onValueChange = { term = it },
            label = { Text("Buscar por nombre o documento") }, modifier = Modifier.fillMaxWidth())
        if (canModify) TextButton(onClick = { creating = true }) { Text("+ Nueva persona externa") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { revision++ }) { Text("Reintentar") } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && error == null && people.isEmpty()) Text("Sin personas externas registradas.")
        LazyColumn(Modifier.weight(1f)) {
            items(people, key = { it.id }) { person ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(person.fullName, style = MaterialTheme.typography.titleMedium)
                        Text(person.identityCard)
                        person.observation?.let { Text(it) }
                        if (canModify) TextButton(onClick = { editing = person }) { Text("Editar") }
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        val person = editing
        var document by remember(person) { mutableStateOf(person?.identityCard.orEmpty()) }
        var name by remember(person) { mutableStateOf(person?.fullName.orEmpty()) }
        var observation by remember(person) { mutableStateOf(person?.observation.orEmpty()) }
        var passport by remember(person) { mutableStateOf(person != null && !CedulaValidator.isValid(person.identityCard)) }
        var saving by remember { mutableStateOf(false) }
        var formError by remember { mutableStateOf<String?>(null) }
        val close = { creating = false; editing = null }
        AlertDialog(onDismissRequest = { if (!saving) close() },
            title = { Text(if (person == null) "Nueva persona externa" else "Editar persona externa") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row { Checkbox(passport, { passport = it }); Text("Es pasaporte") }
                    OutlinedTextField(document, { document = it }, label = { Text("Documento") }, singleLine = true)
                    OutlinedTextField(name, { name = it }, label = { Text("Nombres") })
                    OutlinedTextField(observation, { observation = it }, label = { Text("Observación") })
                    formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    if (document.isBlank() || name.isBlank()) { formError = "Complete el documento y los nombres." }
                    else if (!passport && !CedulaValidator.isValid(document.trim())) { formError = "Cédula inválida." }
                    else {
                        saving = true
                        scope.launch {
                            try {
                                val request = ExternalPersonRequest(document.trim(), name.trim(), observation.trim(), passport)
                                if (person == null) api.createExternalPerson(request)
                                else api.updateExternalPerson(person.id, request)
                                close(); revision++
                            } catch (e: Exception) { formError = e.apiMessage() }
                            finally { saving = false }
                        }
                    }
                }) { Text(if (saving) "Guardando…" else "Guardar") }
            }, dismissButton = { TextButton(enabled = !saving, onClick = close) { Text("Cancelar") } })
    }
}

@Composable
private fun PersonTypesScreen() {
    val api = ApiClient.api(LocalContext.current)
    val scope = rememberCoroutineScope()
    var types by remember { mutableStateOf<List<PersonnelTypeDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<PersonnelTypeDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<PersonnelTypeDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(revision) {
        loading = true; error = null
        try { types = api.personnelTypes(true) }
        catch (e: Exception) { error = e.apiMessage() }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        TextButton(onClick = { creating = true }) { Text("+ Nuevo tipo de persona") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { revision++ }) { Text("Reintentar") } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && error == null && types.isEmpty()) Text("Sin tipos de persona.")
        LazyColumn(Modifier.weight(1f)) {
            items(types, key = { it.id }) { type ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(type.name, style = MaterialTheme.typography.titleMedium)
                        Text("${type.code} · Orden: ${type.sortOrder} · ${if (type.active) "Activo" else "Inactivo"}")
                        Row {
                            TextButton(onClick = { editing = type }) { Text("Editar") }
                            TextButton(enabled = !busy, onClick = { confirming = type }) {
                                Text(if (type.active) "Desactivar" else "Activar")
                            }
                        }
                    }
                }
            }
        }
    }
    confirming?.let { type ->
        AlertDialog(onDismissRequest = { if (!busy) confirming = null },
            title = { Text(if (type.active) "Desactivar tipo de persona" else "Activar tipo de persona") },
            text = { Text("¿Desea ${if (type.active) "desactivar" else "activar"} ${type.name}?") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        api.updatePersonnelType(type.id, PersonnelTypeRequest(type.name, type.sortOrder, !type.active))
                        revision++
                    } catch (e: Exception) { error = e.apiMessage() }
                    finally { busy = false; confirming = null }
                }
            }) { Text("Confirmar") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirming = null }) { Text("Cancelar") } })
    }
    if (creating || editing != null) {
        val type = editing
        var name by remember(type) { mutableStateOf(type?.name.orEmpty()) }
        var order by remember(type) { mutableStateOf((type?.sortOrder ?: (types.size + 1)).toString()) }
        var saving by remember { mutableStateOf(false) }
        var formError by remember { mutableStateOf<String?>(null) }
        val close = { creating = false; editing = null }
        AlertDialog(onDismissRequest = { if (!saving) close() },
            title = { Text(if (type == null) "Nuevo tipo de persona" else "Editar tipo de persona") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre") })
                    OutlinedTextField(order, { order = it }, label = { Text("Orden") }, singleLine = true)
                    formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = !saving, onClick = {
                val number = order.toIntOrNull()
                if (name.isBlank() || name.trim().length > 80 || number == null) {
                    formError = "Ingrese un nombre de hasta 80 caracteres y un orden entero."
                } else {
                    saving = true
                    scope.launch {
                        try {
                            val request = PersonnelTypeRequest(name.trim(), number, type?.active ?: true)
                            if (type == null) api.createPersonnelType(request)
                            else api.updatePersonnelType(type.id, request)
                            close(); revision++
                        } catch (e: Exception) { formError = e.apiMessage() }
                        finally { saving = false }
                    }
                }
            }) { Text(if (saving) "Guardando…" else "Guardar") } },
            dismissButton = { TextButton(enabled = !saving, onClick = close) { Text("Cancelar") } })
    }
}
