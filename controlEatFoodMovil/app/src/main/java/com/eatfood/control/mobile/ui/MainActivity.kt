package com.eatfood.control.mobile.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eatfood.control.mobile.data.model.AuthResponse
import com.eatfood.control.mobile.data.model.LoginRequest
import com.eatfood.control.mobile.data.model.LogoutRequest
import com.eatfood.control.mobile.data.prefs.SessionStore
import com.eatfood.control.mobile.data.remote.ApiClient
import com.eatfood.control.mobile.data.remote.apiMessage
import com.eatfood.control.mobile.ui.kiosk.KioskActivity
import com.eatfood.control.mobile.ui.theme.EatFoodTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EatFoodTheme { AppRoot() } }
    }
}

/** Pantallas del panel (equivale a las rutas del frontend web). */
enum class Screen(val title: String, val roles: List<String>) {
    DASHBOARD("Dashboard", listOf("ADMIN", "SUPERVISOR", "CATERING")),
    EMPLOYEES("Empleados", listOf("ADMIN", "SUPERVISOR")),
    POSITIONS("Cargos", listOf("ADMIN", "SUPERVISOR")),
    CATERINGS("Caterings", listOf("ADMIN", "SUPERVISOR")),
    SCHEDULES("Horarios", listOf("ADMIN")),
    REPORTS("Reportes", listOf("ADMIN", "SUPERVISOR")),
    AUDIT("Auditoría", listOf("ADMIN"))
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val store = remember { SessionStore.get(context) }
    var user by remember { mutableStateOf(store.user) }
    var showSettings by remember { mutableStateOf(false) }

    when {
        showSettings -> SettingsScreen(onClose = { showSettings = false })
        user == null -> LoginScreen(
            onLoggedIn = { user = store.user },
            onSettings = { showSettings = true }
        )
        else -> MainScaffold(
            user = user!!,
            onLogout = { store.clearAuth(); user = null },
            onSettings = { showSettings = true }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(user: AuthResponse, onLogout: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val roles = user.roles ?: emptyList()
    val visibleScreens = remember(roles) { Screen.entries.filter { s -> s.roles.any { it in roles } } }
    var current by remember { mutableStateOf(visibleScreens.firstOrNull() ?: Screen.DASHBOARD) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(24.dp))
                Text("🍽 EatFood", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 20.dp))
                Text("${user.fullName ?: user.username ?: ""} · ${roles.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))
                HorizontalDivider()
                visibleScreens.forEach { s ->
                    NavigationDrawerItem(
                        label = { Text(s.title) },
                        selected = s == current,
                        onClick = { current = s; scope.launch { drawerState.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Pantalla Catering ↗") }, selected = false,
                    onClick = { context.startActivity(Intent(context, KioskActivity::class.java)) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Configuración") }, selected = false,
                    onClick = { scope.launch { drawerState.close() }; onSettings() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Cerrar sesión") }, selected = false,
                    onClick = {
                        val rt = SessionStore.get(context).refreshToken
                        scope.launch {
                            if (rt != null) runCatching { ApiClient.api(context).logout(LogoutRequest(rt)) }
                            onLogout()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(current.title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menú")
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (current) {
                    Screen.DASHBOARD -> DashboardScreen()
                    Screen.EMPLOYEES -> EmployeesScreen(isAdmin = "ADMIN" in roles)
                    Screen.POSITIONS -> PositionsScreen(isAdmin = "ADMIN" in roles)
                    Screen.CATERINGS -> CateringsScreen(isAdmin = "ADMIN" in roles)
                    Screen.SCHEDULES -> SchedulesScreen()
                    Screen.REPORTS -> ReportsScreen()
                    Screen.AUDIT -> AuditScreen()
                }
            }
        }
    }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SessionStore.get(context) }
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().widthIn(max = 440.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🍽 Control de Alimentos", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Usuario") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Contraseña") }, singleLine = true,
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (username.isBlank() || password.isBlank()) { error = "Ingrese usuario y contraseña"; return@Button }
                        error = null; loading = true
                        scope.launch {
                            try {
                                val auth = ApiClient.api(context).login(LoginRequest(username.trim(), password))
                                store.saveAuth(auth)
                                onLoggedIn()
                            } catch (e: Exception) {
                                error = e.apiMessage("No se pudo iniciar sesión")
                            } finally { loading = false }
                        }
                    },
                    enabled = !loading, modifier = Modifier.fillMaxWidth()
                ) { Text(if (loading) "Ingresando…" else "Ingresar") }

                TextButton(onClick = { context.startActivity(Intent(context, KioskActivity::class.java)) }) {
                    Text("Abrir pantalla de Catering")
                }
                TextButton(onClick = onSettings) { Text("⚙ Configurar servidor / lector") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SessionStore.get(context) }
    var serverUrl by remember { mutableStateOf(store.serverUrl) }
    var provider by remember { mutableStateOf(store.biometricProvider) }
    val sdkAvailable = remember { com.eatfood.control.mobile.biometric.ZkBiometricReader.sdkAvailable() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Configuración") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, null) } }
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it },
                label = { Text("URL del servidor (backend)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Emulador: http://10.0.2.2:8080  ·  Teléfono real: http://IP-DEL-PC:8080 (misma red Wi-Fi)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Text("Lector biométrico", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = provider == "zk", onClick = { provider = "zk" })
                Text("ZK9500 por USB-OTG (recomendado)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = provider == "sim", onClick = { provider = "sim" })
                Text("Simulado (sin hardware)")
            }
            Text(
                if (sdkAvailable) "SDK ZKFinger detectado ✓"
                else "SDK ZKFinger no detectado. Agrega el .jar/.aar en app/libs/ para el modo ZK9500.",
                style = MaterialTheme.typography.bodySmall,
                color = if (sdkAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    store.serverUrl = serverUrl
                    store.biometricProvider = provider
                    ApiClient.reset()
                    onClose()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Guardar") }
        }
    }
}
