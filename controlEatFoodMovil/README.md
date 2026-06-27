# Control de Consumo de Alimentos — App Móvil Android

Aplicación móvil Android para la gestión administrativa del sistema de control de
consumo de alimentos por huella digital. Permite a administradores y supervisores
gestionar empleados, cargos y consultar reportes desde un dispositivo móvil.

> Esta app consume la API REST del proyecto hermano **`controlEatFoodWeb`** (backend Spring Boot 3).

---

## 🧱 Arquitectura

```
┌───────────────────────────┐        HTTPS/REST + JWT        ┌──────────────────────────┐
│  App Android (Kotlin)     │  ──────────────────────────►   │  Backend Spring Boot 3    │
│  - Jetpack Compose UI     │                               │  (proyecto controlEat-    │
│  - Stateful Composables   │ ◄──────────────────────────   │   FoodWeb)                │
│  - Retrofit + OkHttp      │                               │                          │
│  - EncryptedSharedPrefs   │                               │                          │
│  - Room (cola offline)    │                               │                          │
└───────────────────────────┘                               └──────────────────────────┘
```

**Patrón:** stateful Composables + `ApiClient` singleton (Retrofit). Estado en `remember + mutableStateOf` + `LaunchedEffect`; corrutinas con `rememberCoroutineScope`. Navegación por estado `Screen` enum dentro de `MainScaffold` (drawer). No usa ViewModels separados ni Navigation-Compose con nav-graph.

---

## 📁 Estructura del proyecto

```
controlEatFoodMovil/
├── app/
│   └── src/main/java/com/eatfood/control/mobile/
│       ├── EatFoodApp.kt            # Application class
│       ├── biometric/               # Lector biométrico ZK (USB OTG)
│       │   ├── BiometricReader.kt   # Interfaz
│       │   ├── ZkBiometricReader.kt # Implementación SDK ZK
│       │   ├── SimBiometricReader.kt# Simulador para desarrollo
│       │   └── UsbPermission.kt     # Permisos USB
│       ├── data/
│       │   ├── db/PendingScan.kt    # Room DB para escaneos offline
│       │   ├── model/Models.kt      # Data classes
│       │   ├── prefs/SessionStore.kt# DataStore para sesión/tokens
│       │   └── remote/              # Retrofit API
│       │       ├── ApiClient.kt     # OkHttp + JWT interceptor
│       │       ├── ApiService.kt    # Endpoints REST
│       │       ├── ScanApiService.kt# Endpoints de escaneo
│       │       └── ApiErrors.kt     # Manejo de errores
│       ├── ui/
│       │   ├── MainActivity.kt      # Activity principal + NavHost
│       │   ├── AdminScreens.kt      # Pantallas admin (empleados, cargos)
│       │   ├── ReportScreens.kt     # Pantallas de reportes
│       │   ├── Common.kt            # Componentes reutilizables
│       │   ├── kiosk/KioskActivity.kt # Modo kiosco
│       │   └── theme/Theme.kt       # Material3 tema
│       └── util/ToneFeedback.kt     # Feedback sonoro
├── build.gradle.kts                 # Root build
├── app/build.gradle.kts             # App module build
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml           # Version catalog
├── gradlew / gradlew.bat
└── local.properties.example
```

---

## ✅ Requisitos previos

| Componente | Versión | Notas |
|-----------|---------|-------|
| Android Studio | Ladybug+ | IDE recomendado |
| JDK | 17+ (probado con 21) | para Gradle |
| Android SDK | API 34 (compileSdk) | minSdk 29 (Android 10) |
| Backend corriendo | - | Proyecto `controlEatFoodWeb` levantado |

---

## 🚀 Puesta en marcha

### 1. Levantar el backend

Asegúrate de que el backend del proyecto `controlEatFoodWeb` esté corriendo en `http://localhost:8080`.

### 2. Configurar la URL del API

```bash
cp local.properties.example local.properties
```

Edita `local.properties`:
```properties
sdk.dir=C\:\\Users\\TU_USUARIO\\AppData\\Local\\Android\\Sdk
API_BASE_URL=http://10.0.2.2:8080/api
```

> `10.0.2.2` es el alias de `localhost` del host desde el emulador Android.
> Para un dispositivo físico, usa la IP de tu máquina en la red local.

### 3. Compilar y ejecutar

```bash
./gradlew :app:assembleDebug
```

O abre el proyecto en Android Studio y ejecuta directamente.

### 4. Credenciales

| Rol | Usuario | Contraseña |
|-----|---------|-----------|
| Administrador | `admin` | `Admin123*` |
| Catering (1 por catering) | `cateringNorte`, `cateringCentro`, `cateringSur` | `catering123` |

> Las contraseñas se cifran con BCrypt al primer arranque del backend.

---

## 📱 Funcionalidades

| Pantalla | Descripción |
|----------|-------------|
| **Login** | Autenticación con JWT (mismas credenciales que el panel web) |
| **Dashboard** | Resumen de consumos del día + tendencia de 7 días |
| **Empleados** | CRUD de empleados (crear, editar, inactivar, gestionar huellas) |
| **Cargos** | CRUD de cargos/posiciones |
| **Caterings** | CRUD de caterings |
| **Horarios** | Editar horarios de Almuerzo/Merienda |
| **Almuerzos Extra** | Registro manual de consumos para empleados existentes (búsqueda por nombre) o **personas externas** (cédula + nombre), con selector de catering y tipo de comida (Almuerzo/Merienda). No valida horario, permiso ni duplicado. |
| **Reportes** | Consulta por fecha/catering/comida, exportación a CSV/Excel/PDF |
| **Auditoría** | Log de acciones críticas con filtros |
| **Kiosco** | Modo kiosco con lector biométrico USB OTG + cola offline (Room) |

---

## 🔗 Endpoints consumidos

| Función | Método | Endpoint |
|---------|--------|----------|
| Login | POST | `/auth/login` |
| Refresh token | POST | `/auth/refresh` |
| Logout | POST | `/auth/logout` |
| Dashboard | GET | `/reports/dashboard` |
| Tendencia | GET | `/reports/trend` |
| Consumos | GET | `/reports/consumptions` |
| Exportación | GET | `/reports/export` |
| Auditoría | GET | `/audit` |
| Listar empleados | GET | `/employees` |
| Crear empleado | POST | `/employees` |
| Actualizar empleado | PUT | `/employees/{id}` |
| Inactivar empleado | DELETE | `/employees/{id}` |
| Huellas por empleado | GET | `/fingerprints/employee/{id}` |
| Enrolar huella | POST | `/fingerprints/enroll` |
| Eliminar huella | DELETE | `/fingerprints/{id}` |
| Listar cargos | GET | `/positions` |
| CRUD cargo | POST/PUT | `/positions` |
| Listar caterings | GET | `/caterings` |
| CRUD catering | POST/PUT | `/caterings` |
| Tipos de comida | GET | `/meal-types` |
| Horarios | GET/POST | `/schedules` |
| Registro manual (empleado) | POST | `/manual-consumptions` |
| Registro persona externa | POST | `/manual-consumptions/external` |
| Conectar dispositivo | POST | `/scan/connect` |
| Escanear huella | POST | `/scan` |
| Sincronizar offline | POST | `/scan/sync` |
| Feed del día | GET | `/scan/today` |
| Desconectar | POST | `/scan/disconnect` |

---

## 🛠 Tecnologías

- **Kotlin** 2.2
- **Jetpack Compose** (Material3)
- **Retrofit 2.11** + **OkHttp 4.12** (networking) + interceptor JWT + `TokenAuthenticator` (refresh automático)
- **Gson** (serialización)
- **EncryptedSharedPreferences** (almacenamiento seguro de tokens/sesión)
- **Coroutines** (asincronía)
- **Room** (base de datos local para cola offline del kiosco)
- **SDK ZKFinger** (lectura biométrica USB OTG, JARs en `app/libs/`)
- **Stateful Composables** (estado en `remember` + `LaunchedEffect`, sin ViewModels separados)

---

## ⚠️ Notas

- La app requiere que el backend esté corriendo para funcionar.
- Para biometría real, se necesita un lector ZK conectado vía USB OTG al dispositivo Android.
- Para desarrollo sin hardware, se usa el `SimBiometricReader` automáticamente.
