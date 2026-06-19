# API REST — Control de Consumo de Alimentos

Base: `http://localhost:8080`  ·  Documentación interactiva: `/swagger-ui.html`
Autenticación: `Authorization: Bearer <accessToken>` (salvo `/api/auth/**` y `/api/scan/**`).

## Autenticación

| Método | Ruta | Descripción | Cuerpo |
|-------|------|-------------|--------|
| POST | `/api/auth/login` | Inicia sesión | `{username, password}` → `{accessToken, refreshToken, roles, ...}` |
| POST | `/api/auth/refresh` | Renueva tokens | `{refreshToken}` |
| POST | `/api/auth/logout` | Revoca refresh token | `{refreshToken}` |

## Empleados — `ADMIN` (escritura), `SUPERVISOR` (lectura)

| Método | Ruta | Descripción |
|-------|------|-------------|
| GET | `/api/employees?term=&page=&size=` | Lista/búsqueda paginada |
| GET | `/api/employees/{id}` | Detalle |
| POST | `/api/employees` | Crear |
| PUT | `/api/employees/{id}` | Actualizar |
| DELETE | `/api/employees/{id}` | Inactivar (soft-delete; conserva historial) |

`EmployeeRequest`: `{identityCard, fullName, positionId?, status?, allowedPlates?, allowsLunch?, allowsSnack?}`

## Huellas — `ADMIN`

| Método | Ruta | Descripción |
|-------|------|-------------|
| GET | `/api/fingerprints/employee/{employeeId}` | Lista huellas activas |
| POST | `/api/fingerprints/enroll` | Enrola huella `{employeeId, fingerIndex, templateB64}` (máx. 3) |
| DELETE | `/api/fingerprints/{id}` | Desactiva huella |

## Catálogos

| Método | Ruta | Rol |
|-------|------|-----|
| GET/POST/PUT | `/api/positions` `/api/positions/{id}` | lectura `ADMIN/SUPERVISOR`, escritura `ADMIN` |
| GET/POST/PUT | `/api/caterings` `/api/caterings/{id}` | igual |
| GET | `/api/meal-types` | autenticado |
| POST | `/api/meal-types` | `ADMIN` |
| GET | `/api/schedules` | autenticado |
| POST | `/api/schedules` | `ADMIN` (upsert por tipo de comida) |

## Catering / Escaneo — token de dispositivo (no JWT)

| Método | Ruta | Descripción |
|-------|------|-------------|
| POST | `/api/scan/connect` | Conecta dispositivo `{cateringUsername, cateringPassword, deviceUid, deviceName?}` → `{sessionToken,...}`. Máx. 2 simultáneos. |
| POST | `/api/scan/disconnect?sessionToken=` | Desconecta |
| POST | `/api/scan` | Procesa huella y registra consumo |
| POST | `/api/scan/sync` | Sincroniza lote offline |

`ScanRequest`: `{sessionToken, templateB64, mealTypeCode?, clientUuid?, offline?, consumedAt?}`

`ScanResponse.status`: `SUCCESS` · `NOT_FOUND` ("HUELLA NO ENCONTRADA") · `OUT_OF_SCHEDULE`
("FUERA DEL HORARIO PERMITIDO") · `DUPLICATE` ("ALMUERZO/MERIENDA YA REGISTRADO") · `NOT_ALLOWED`.

## Reportes y estadísticas — `ADMIN`/`SUPERVISOR`

| Método | Ruta | Descripción |
|-------|------|-------------|
| GET | `/api/reports/dashboard?date=` | Estadísticas del día |
| GET | `/api/reports/consumptions?from=&to=&cateringId=&mealTypeId=&employeeId=` | Detalle de consumos |
| GET | `/api/reports/not-consumed?date=` | Empleados que no consumieron |
| GET | `/api/reports/trend?from=&to=` | Tendencia de consumo |
| GET | `/api/reports/export?format=csv|excel|pdf&from=&to=&...` | Exportación |

## Auditoría — `ADMIN`

| Método | Ruta | Descripción |
|-------|------|-------------|
| GET | `/api/audit?entity=&page=&size=` | Bitácora de cambios |

## Códigos de error (formato)

```json
{ "timestamp": "...", "status": 409, "code": "DUPLICATE_CARD", "message": "..." }
```

| HTTP | Código | Situación |
|------|--------|-----------|
| 401 | `BAD_CREDENTIALS` | Login inválido |
| 409 | `ACCOUNT_LOCKED` | Bloqueo por fuerza bruta |
| 409 | `DEVICE_LIMIT` | Límite de dispositivos del catering |
| 409 | `MAX_FINGERPRINTS` | Más de 3 huellas |
| 404 | `NOT_FOUND` | Recurso inexistente |
| 400 | `VALIDATION` | Error de validación de campos |
