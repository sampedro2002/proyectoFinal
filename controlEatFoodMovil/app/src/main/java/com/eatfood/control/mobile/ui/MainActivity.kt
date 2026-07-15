package com.eatfood.control.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eatfood.control.mobile.R
import com.eatfood.control.mobile.data.model.AuthResponse
import com.eatfood.control.mobile.data.model.LoginRequest
import com.eatfood.control.mobile.data.model.LogoutRequest
import com.eatfood.control.mobile.data.prefs.SessionStore
import com.eatfood.control.mobile.data.remote.ApiClient
import com.eatfood.control.mobile.data.remote.apiMessage
import com.eatfood.control.mobile.data.remote.isConnectivityError
import com.eatfood.control.mobile.ui.theme.EatFoodTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { EatFoodTheme { AppRoot() } }
    }
}

/** Pantallas del panel (equivale a las rutas del frontend web). */
enum class Screen(val title: String, val roles: List<String>) {
    MY_RESTAURANT("Mi Restaurante", listOf("CATERING")),
    DASHBOARD("Dashboard", listOf("ADMIN")),
    EMPLOYEES("Empleados", listOf("ADMIN")),
    RESTAURANTS("Restaurantes", listOf("ADMIN")),
    USERS("Usuarios", listOf("ADMIN")),
    SCHEDULES("Horarios", listOf("ADMIN")),
    REPORTS("Reportes", listOf("ADMIN")),
    AUDIT("Auditoría", listOf("ADMIN")),
    EXTRA_MEALS("Registro manual", listOf("ADMIN"))
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val store = remember { SessionStore.get(context) }
    var user by remember { mutableStateOf(store.user) }
    var showSettings by remember { mutableStateOf(false) }

    // Retroceder desde el login admin vuelve a la pantalla de Restaurant (pantalla de arranque)
    // en lugar de cerrar la app.
    BackHandler(enabled = !showSettings && user == null) {
        activity?.let {
            it.startActivity(Intent(it, com.eatfood.control.mobile.ui.kiosk.KioskActivity::class.java))
            it.finish()
        }
    }

    // Solicitar permiso de notificaciones en Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (!isGranted) {
                // El usuario rechazó las notificaciones; el permiso USB se pedirá igual,
                // pero no saldrá la notificación de apoyo.
                Log.w("MainActivity", "Permiso de notificaciones denegado")
            }
        }
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_logo),
                        contentDescription = "EatFood",
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("EatFood", style = MaterialTheme.typography.titleLarge)
                        Text("Control de Alimentos", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val roleLabels = roles.joinToString(", ") { when (it) { "ADMIN" -> "Administrador"; "CATERING" -> "Restaurante"; else -> it } }
                Text("${user.fullName ?: user.username ?: ""} · $roleLabels",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 12.dp))
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
                    Screen.MY_RESTAURANT -> MyRestaurantScreen()
                    Screen.DASHBOARD -> DashboardScreen()
                    Screen.EMPLOYEES -> EmployeesScreen(canModify = "ADMIN" in roles)
                    Screen.RESTAURANTS -> RestaurantsScreen(isAdmin = "ADMIN" in roles)
                    Screen.USERS -> UsersScreen()
                    Screen.SCHEDULES -> SchedulesScreen()
                    Screen.REPORTS -> ReportsScreen()
                    Screen.AUDIT -> AuditScreen()
                    Screen.EXTRA_MEALS -> ExtraMealsScreen()
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
    // Prellenar "admin" solo en builds de desarrollo (mismo criterio que la web con import.meta.env.DEV).
    var username by remember { mutableStateOf(if (com.eatfood.control.mobile.BuildConfig.DEBUG) "admin" else "") }
    var password by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().widthIn(max = 440.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = "EatFood",
                    modifier = Modifier.size(88.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("EatFood", style = MaterialTheme.typography.headlineSmall)
                Text("Control de Consumo de Alimentos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
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
                                error = e.apiMessage("No se pudo iniciar sesión") +
                                    if (e.isConnectivityError())
                                        "\nServidor: ${store.serverUrl}\nVerifique que el backend esté encendido y que el firewall del servidor permita el puerto."
                                    else ""
                            } finally { loading = false }
                        }
                    },
                    enabled = !loading, modifier = Modifier.fillMaxWidth()
                ) { Text(if (loading) "Ingresando…" else "Ingresar") }

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
    val sdkAvailable = remember { com.eatfood.control.mobile.biometric.ZkBiometricReader.sdkAvailable() }

    // Aprovisiona la URL del servidor escaneando un QR. La dirección NO se puede teclear
    // ni borrar manualmente: solo se establece escaneando un código válido.
    fun scanServerQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue?.trim().orEmpty()
                if (raw.isBlank()) {
                    Toast.makeText(context, "El QR está vacío o es ilegible.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                store.serverUrl = raw          // el setter normaliza (añade http:// y quita '/')
                serverUrl = store.serverUrl
                ApiClient.reset()
                Toast.makeText(context, "Servidor configurado: ${store.serverUrl}", Toast.LENGTH_LONG).show()
            }
            .addOnCanceledListener { /* el usuario canceló el escaneo */ }
            .addOnFailureListener { e ->
                Toast.makeText(context, "No se pudo escanear el QR: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Configuración") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, null) } }
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState())
        ) {
            // Campo de solo lectura: la dirección se aprovisiona por QR y el usuario no la edita.
            OutlinedTextField(
                value = serverUrl, onValueChange = {}, readOnly = true,
                label = { Text("URL del servidor (backend)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "La dirección solo se configura escaneando el QR que genera el administrador.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { scanServerQr() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Escanear QR del servidor")
            }
            Spacer(Modifier.height(24.dp))
            Text("Lector biométrico", style = MaterialTheme.typography.titleMedium)
            Text("ZK9500 por USB-OTG", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (sdkAvailable) "SDK ZKFinger detectado ✓"
                else "SDK ZKFinger no detectado. Agrega el .jar/.aar en app/libs/.",
                style = MaterialTheme.typography.bodySmall,
                color = if (sdkAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cerrar") }
        }
    }
}

@Composable
fun MyRestaurantScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val store = remember { SessionStore.get(context) }
    val user = store.user

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().widthIn(max = 440.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Restaurant,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text("Mi Restaurante", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Bienvenido, ${user?.fullName ?: user?.username ?: ""}",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "Para registrar consumos, abre el modo kiosco y conecta el lector biométrico.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        activity?.let {
                            it.startActivity(Intent(it, com.eatfood.control.mobile.ui.kiosk.KioskActivity::class.java))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PointOfSale, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Abrir Modo Kiosco")
                }
            }
        }
    }
}
