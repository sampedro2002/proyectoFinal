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
│  - MVVM + ViewModels      │ ◄──────────────────────────   │   FoodWeb)                │
│  - Retrofit + OkHttp      │                               │                          │
└───────────────────────────┘                               └──────────────────────────┘
```

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
| JDK | 17+ | incluido con Android Studio |
| Android SDK | API 35 (compileSdk) | minSdk 28 (Android 9) |
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

---

## 📱 Funcionalidades

| Pantalla | Descripción |
|----------|-------------|
| **Login** | Autenticación con JWT |
| **Dashboard** | Resumen de consumos del día + registros recientes |
| **Empleados** | CRUD de empleados (crear, editar, eliminar) |
| **Cargos** | CRUD de cargos/posiciones |
| **Reportes** | Consulta por empleado y por cargo con filtros de fecha |
| **Kiosco** | Modo kiosco con lector biométrico USB OTG |

---

## 🔗 Endpoints consumidos

| Función | Método | Endpoint |
|---------|--------|----------|
| Login | POST | `/auth/login` |
| Refresh token | POST | `/auth/refresh` |
| Dashboard | GET | `/reports/summary` |
| Reporte diario | GET | `/reports/daily` |
| Listar empleados | GET | `/employees` |
| Crear empleado | POST | `/employees` |
| Actualizar empleado | PUT | `/employees/{id}` |
| Eliminar empleado | DELETE | `/employees/{id}` |
| Listar cargos | GET | `/positions` |
| Crear cargo | POST | `/positions` |
| Actualizar cargo | PUT | `/positions/{id}` |
| Eliminar cargo | DELETE | `/positions/{id}` |
| Reporte por empleado | GET | `/reports/by-employee` |
| Reporte por cargo | GET | `/reports/by-position` |
| Conectar dispositivo | POST | `/scan/connect` |
| Escanear huella | POST | `/scan` |
| Sincronizar offline | POST | `/scan/sync` |
| Desconectar | POST | `/scan/disconnect` |

---

## 🛠 Tecnologías

- **Kotlin** 2.0
- **Jetpack Compose** (Material3)
- **Retrofit 2** + **OkHttp 4** (networking)
- **Gson** (serialización)
- **DataStore Preferences** (almacenamiento de tokens)
- **Compose Navigation** (navegación)
- **MVVM** (patrón arquitectónico)
- **Room** (base de datos local para escaneos offline)
- **SDK ZKFinger** (lectura biométrica USB OTG)

---

## ⚠️ Notas

- La app requiere que el backend esté corriendo para funcionar.
- Para biometría real, se necesita un lector ZK conectado vía USB OTG al dispositivo Android.
- Para desarrollo sin hardware, se usa el `SimBiometricReader` automáticamente.
