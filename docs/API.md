# API REST — Control de Consumo de Alimentos

Base: `http://localhost:3000`  ·  Documentación interactiva: `/swagger-ui.html`
Autenticación: `Authorization: Bearer <accessToken>` (salvo `/api/auth/**` y `/api/scan/**`).

## Autenticación

| Método | Ruta | Descripción | Cuerpo |
|-------|------|-------------|--------|
| POST | `/api/auth/login` | Inicia sesión | `{username, password}` → `{accessToken, refreshToken, roles, ...}` |
| POST | `/api/auth/refresh` | Renueva tokens | `{refreshToken}` |
| POST | `/api/auth/logout` | Revoca refresh token | `{refreshToken}` |

## Empleados — `ADMIN`

| Método | Ruta | Descripción |
|-------|------|-------------|
| GET | `/api/employees?term=&page=&size=` | Lista/búsqueda paginada |
| GET | `/api/employees/{id}` | Detalle |
| POST | `/api/employees` | Crear |
| PUT | `/api/employees/{id}` | Actualizar |
| DELETE | `/api/employees/{id}` | Inactivar (soft-delete; conserva historial) |

`EmployeeRequest`: `{identityCard, fullName, observation?, status?, allowsLunch?, allowsSnack?}`

## Huellas — `ADMIN`

| Método | Ruta | Descripción |
|-------|------|-------------|
| GET | `/api/fingerprints/employee/{employeeId}` | Lista huellas activas |
| POST | `/api/fingerprints/enroll` | Enrola huella `{employeeId, fingerIndex, templateB64}` (máx. 3) |
| POST | `/api/fingerprints/enroll-from-server/{employeeId}/{fingerIndex}` | Captura desde lector ZK9500 del servidor y enrola (requiere lector conectado al servidor) |
| DELETE | `/api/fingerprints/{id}` | Desactiva huella |

## Catálogos

| Método | Ruta | Rol |
|-------|------|-----|
| GET/POST/PUT | `/api/restaurants` `/api/restaurants/{id}` | lectura `ADMIN`, escritura `ADMIN` |
| GET | `/api/schedules` | autenticado |
| POST | `/api/schedules` | `ADMIN` (upsert del horario general) |

## Restaurante / Escaneo — token de dispositivo (no JWT)

| Método | Ruta | Descripción |
|-------|------|-------------|
| POST | `/api/scan/connect` | Conecta dispositivo `{restaurantUsername, restaurantPassword, deviceUid, deviceName?}` → `{sessionToken,...}`. Máx. 2 simultáneos. |
| POST | `/api/scan/disconnect?sessionToken=` | Desconecta |
| POST | `/api/scan` | Procesa huella y registra consumo |
| POST | `/api/scan/sync` | Sincroniza lote offline |

`ScanRequest`: `{sessionToken, templateB64, mealTypeCode?, clientUuid?, offline?, consumedAt?}`

`ScanResponse.status`: `SUCCESS` · `NOT_FOUND` ("HUELLA NO ENCONTRADA") · `OUT_OF_SCHEDULE`
("FUERA DEL HORARIO PERMITIDO") · `DUPLICATE` ("ALMUERZO/MERIENDA YA REGISTRADO") · `NOT_ALLOWED`.

## Registro manual — `ADMIN`

End point para "retira por otro" y persona externa. No usan JWT del dispositivo
(sí JWT de ADMIN).

| Método | Ruta | Descripción |
|-------|------|-------------|
| POST | `/api/manual-consumptions` | Registra "retira por otro". Un empleado (retirador) retira comidas a nombre de uno o varios titulares; se crea una fila `consumo` por cada (titular × tipo de comida) con `method='MANUAL'`, `empleado_apoderado_id=<retirador>` y `observacion="<Retirador> retira de <Titular>"` autogenerada. No valida horario, permisos ni duplicados (override admin). |
| POST | `/api/manual-consumptions/external` | Persona externa (visitante/contratista). Crea/reutiliza `Employee` con `status='INACTIVE'`; registra un consumo con `method='EXTERNAL'`. |

`ManualScanRequest`:
```json
{
  "proxyEmployeeId": 7,
  "restaurantId": 2,
  "titulars": [
    { "employeeId": 10, "mealTypeCodes": ["BREAKFAST", "LUNCH"] },
    { "employeeId": 15, "mealTypeCodes": ["LUNCH"] }
  ]
}
```
`mealTypeCodes` aceptados: `BREAKFAST` (Almuerzo) y `LUNCH`/`SNACK` (Merienda).

`ManualScanResponse`:
```json
{ "status": "SUCCESS", "message": "3 registro(s) creado(s) por Pepe",
  "employeeName": "Pepe", "mealName": "Merienda", "created": 3 }
```

`ExternalScanRequest`:
```json
{ "identityCard": "1712345678", "isPassport": false, "fullName": "Juan Pérez",
  "mealTypeCode": "BREAKFAST", "restaurantId": 2, "observation": null }
```

## Reportes y estadísticas — `ADMIN`

| Método | Ruta | Descripción |
|-------|------|-------------|
| GET | `/api/reports/dashboard?date=` | Estadísticas del día |
| GET | `/api/reports/consumptions?from=&to=&restaurantId=&employeeId=&method=` | Detalle de consumos. `method` puede repetirse (`?method=FINGERPRINT&method=MANUAL`) para combinar; valores: `FINGERPRINT`, `MANUAL`, `EXTERNAL`. |
| GET | `/api/reports/not-consumed?date=` | Empleados que no consumieron |
| GET | `/api/reports/trend?from=&to=` | Tendencia de consumo |
| GET | `/api/reports/export?format=csv|excel|pdf&from=&to=&...&method=` | Exportación con los mismos filtros que `/consumptions` (incluye `method`). Las filas `MANUAL` salen en amarillo y `EXTERNAL` en naranja en Excel/PDF. |

`ConsumptionRow` (respuesta de `/consumptions`):
```json
{
  "id": 123, "businessDate": "2026-07-17", "consumedAt": "2026-07-17T12:10:00-05:00",
  "employeeName": "Juan", "identityCard": "1712345678",
  "restaurantName": "Norte", "mealName": "Almuerzo",
  "observation": "Pepe retira de Juan", "offline": false,
  "method": "MANUAL", "proxyEmployeeName": "Pepe"
}

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
| 409 | `DEVICE_LIMIT` | Límite de dispositivos del restaurante |
| 409 | `MAX_FINGERPRINTS` | Más de 3 huellas |
| 404 | `NOT_FOUND` | Recurso inexistente |
| 400 | `VALIDATION` | Error de validación de campos |
