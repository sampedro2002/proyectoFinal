# API Móvil — Endpoints Consumidos por controlEatFoodMovil

La aplicación móvil Android consume la API REST provista por el backend de **`controlEatFoodWeb`** (Spring Boot 3). A continuación se documentan los endpoints utilizados por la aplicación, organizados según su propósito.

---

## 🔐 1. Autenticación (`ApiService`)
Rutas base para control de sesión. La URL base se configura en `local.properties` (por defecto `http://10.0.2.2:8080/api`).

### Iniciar Sesión
- **Ruta:** `POST /api/auth/login`
- **Cuerpo:** `LoginRequest` (usuario, contraseña)
- **Respuesta:** `AuthResponse` con `accessToken`, `refreshToken` y datos básicos del usuario.

### Refrescar Token
- **Ruta:** `POST /api/auth/refresh`
- **Cuerpo:** `RefreshRequest` (refreshToken)
- **Respuesta:** `AuthResponse` con un nuevo par de tokens.

### Cerrar Sesión
- **Ruta:** `POST /api/auth/logout`
- **Cuerpo:** `LogoutRequest` (refreshToken)
- **Respuesta:** `200 OK` (vacío)

---

## 👥 2. Gestión de Empleados (`ApiService`)
Requiere cabecera `Authorization: Bearer <accessToken>` (Rol `ADMIN` o `SUPERVISOR`).

### Listar Empleados
- **Ruta:** `GET /api/employees`
- **Query Params:**
  - `term` (opcional): Filtro por nombre/cédula.
  - `page` (default 0): Número de página.
  - `size` (default 100): Cantidad por página.
- **Respuesta:** `Page<EmployeeResponse>`

### Obtener Empleado por ID
- **Ruta:** `GET /api/employees/{id}`
- **Respuesta:** `EmployeeResponse`

### Crear Empleado
- **Ruta:** `POST /api/employees`
- **Cuerpo:** `EmployeeRequest` (cédula, nombres, cargoId, etc.)
- **Respuesta:** `EmployeeResponse`

### Actualizar Empleado
- **Ruta:** `PUT /api/employees/{id}`
- **Cuerpo:** `EmployeeRequest`
- **Respuesta:** `EmployeeResponse`

### Eliminar Empleado
- **Ruta:** `DELETE /api/employees/{id}`
- **Respuesta:** `200 OK` (vacío)

---

## 👣 3. Gestión de Huellas Digitales (`ApiService`)
Gestión de plantillas biométricas del SDK ZK9500 para identificación 1:N.

### Listar Huellas por Empleado
- **Ruta:** `GET /api/fingerprints/employee/{employeeId}`
- **Respuesta:** `List<FingerprintResponse>`

### Enrolar Huella
- **Ruta:** `POST /api/fingerprints/enroll`
- **Cuerpo:** `EnrollRequest` (employeeId, fingerIndex, templateBase64)
- **Respuesta:** `FingerprintResponse`

### Eliminar Huella
- **Ruta:** `DELETE /api/fingerprints/{id}`
- **Respuesta:** `200 OK` (vacío)

---

## 📁 4. Catálogos y Parámetros (`ApiService`)

### Cargos
- `GET /api/positions` → Listar cargos.
- `POST /api/positions` → Crear cargo.
- `PUT /api/positions/{id}` → Editar cargo.

### Caterings
- `GET /api/caterings` → Listar servicios de catering.
- `POST /api/caterings` → Registrar catering.
- `PUT /api/caterings/{id}` → Editar catering.

### Tipos de Comida (Almuerzo / Merienda)
- `GET /api/meal-types` → Listar tipos de comida con sus horarios configurados.

### Horarios
- `GET /api/schedules` → Listar horarios.
- `POST /api/schedules` → Guardar/actualizar horario.

---

## 📊 5. Reportes y Dashboard (`ApiService`)

### Estadísticas del Dashboard (Caterings del día)
- **Ruta:** `GET /api/reports/dashboard`
- **Query Params:** `date` (opcional, formato `yyyy-MM-dd`)
- **Respuesta:** `DashboardStats`

### Tendencias de Consumo (Gráficos)
- **Ruta:** `GET /api/reports/trend`
- **Query Params:** `from` (fecha inicio), `to` (fecha fin)
- **Respuesta:** `List<TrendPoint>`

### Listado Detallado de Consumos
- **Ruta:** `GET /api/reports/consumptions`
- **Query Params:** `from`, `to`, `cateringId` (opcional), `mealTypeId` (opcional), `employeeId` (opcional).
- **Respuesta:** `List<ConsumptionRow>`

### Exportar Reportes (Excel / PDF)
- **Ruta:** `GET /api/reports/export`
- **Query Params:** `format` (`excel` | `pdf`), `from`, `to`, `cateringId` (opcional), `mealTypeId` (opcional).
- **Respuesta:** Archivo binario (`xlsx` / `pdf`) como flujo de bytes.

---

## 📡 6. Punto de Catering / Escaneo (`ScanApiService`)
Estos endpoints se utilizan en el modo Kiosco/Catering de la app móvil. **No utilizan autenticación JWT de usuario**, sino un token de dispositivo (`sessionToken`) obtenido al conectarse.

### Conectar Dispositivo (Catering Login)
- **Ruta:** `POST /api/scan/connect`
- **Cuerpo:** `DeviceConnectRequest` (cateringId, aliasDispositivo, clientUuid, password)
- **Respuesta:** `DeviceConnectResponse` con `sessionToken` y nombre del catering.

### Desconectar Dispositivo
- **Ruta:** `POST /api/scan/disconnect`
- **Query Params:** `sessionToken`
- **Respuesta:** `200 OK` (vacío)

### Registrar Escaneo (Identificación 1:N)
- **Ruta:** `POST /api/scan`
- **Cuerpo:** `ScanRequest` (sessionToken, template biométrica, clientUuid de idempotencia, timestamp, coordenadas GPS opcionales).
- **Respuesta:** `ScanResponse` (resultado: `SUCCESS`, `DUPLICATE`, `OUT_OF_SCHEDULE`, `NOT_FOUND`, `NOT_ALLOWED` junto con el nombre del empleado).

### Sincronizar Lotes Offline
- **Ruta:** `POST /api/scan/sync`
- **Cuerpo:** `SyncBatchRequest` (lista de `ScanRequest` guardados offline)
- **Respuesta:** `SyncBatchResponse` con el detalle de procesados, duplicados y erróneos.
