# Arquitectura — Control de Consumo de Alimentos (ZK9500)

## 1. Visión general

Sistema cliente-servidor de 3 capas con dispositivos de borde (catering):

- **Frontend PWA (React + Vite):** panel de administración/supervisión y pantalla de
  catering en modo kiosco; instalable y operable offline.
- **Backend (Spring Boot 3, Java 21):** API REST stateless con JWT, lógica de negocio,
  motor de identificación biométrica 1:N, auditoría, reportes y sincronización.
- **MySQL 8:** persistencia transaccional.
- **Dispositivos de catering:** navegador en modo kiosco + agente local **ZKFinger WebAPI**
  conectado al lector **ZK9500** por USB.

```
Empleado → [Lector ZK9500] → ZKFinger WebAPI (WebSocket local)
                                   │ template (base64)
                                   ▼
                         Frontend PWA (kiosco)
                                   │ REST /api/scan  (+ cola offline)
                                   ▼
                    Backend Spring Boot ── 1:N (libzkfp/JNA) ── índice de plantillas
                                   │
                                   ▼
                              MySQL 8
```

## 2. Modelo Entidad-Relación

```mermaid
erDiagram
    ROLES ||--o{ USUARIO_ROL : tiene
    USUARIO ||--o{ USUARIO_ROL : posee
    USUARIO }o--|| RESTAURANTE : opera
    EMPLEADO ||--o{ HUELLA_DIGITAL : registra
    EMPLEADO ||--o{ CONSUMO : genera
    RESTAURANTE ||--o{ DISPOSITIVO : conecta
    RESTAURANTE ||--o{ CONSUMO : atiende
    DISPOSITIVO ||--o{ CONSUMO : captura

    USUARIO {
        bigint id PK
        varchar nombre_usuario UK
        varchar contrasena_hash
        varchar nombre_completo
        varchar correo
        boolean habilitado
        int intentos_fallidos
        datetime bloqueado_hasta
        bigint restaurante_id FK
    }
    EMPLEADO {
        bigint id PK
        varchar cedula UK
        varchar nombre_completo
        varchar estado
        varchar observacion
        boolean permite_almuerzo
        boolean permite_merienda
        boolean eliminado
    }
    HUELLA_DIGITAL {
        bigint id PK
        bigint empleado_id FK
        smallint indice_dedo
        blob plantilla
        bigint registrado_por
        boolean activo
    }
    RESTAURANTE {
        bigint id PK
        varchar nombre UK
        varchar ubicacion
        varchar representante
        boolean activo
        int max_dispositivos
    }
    DISPOSITIVO {
        bigint id PK
        bigint restaurante_id FK
        varchar uid_dispositivo
        varchar nombre
        varchar token_sesion
        boolean conectado
    }
    HORARIO {
        bigint id PK
        time hora_inicio
        time hora_fin
        boolean activo
    }
    CONSUMO {
        bigint id PK
        bigint empleado_id FK
        bigint restaurante_id FK
        bigint dispositivo_id FK
        datetime consumido_en
        date fecha_negocio
        varchar uuid_cliente UK
        varchar estado_sincronizacion
        boolean sin_conexion
        varchar metodo
        varchar nombre_comida
        varchar observacion
        bigint empleado_apoderado_id FK
    }
    REGISTRO_AUDITORIA {
        bigint id PK
        varchar nombre_usuario
        varchar nombre_entidad
        varchar accion
        text valor_anterior
        text valor_nuevo
        varchar direccion_ip
        varchar info_dispositivo
    }
    SESION_INICIO {
        bigint id PK
        bigint usuario_id FK
        varchar token_refresco
        varchar direccion_ip
        varchar info_dispositivo
        datetime emitido_en
        datetime expira_en
        boolean revocado
    }
```

**Restricciones e índices clave:**
- `UNIQUE(uuid_cliente)` → idempotencia de sincronización offline.
- Máximo 3 huellas activas por empleado (controlado en FingerprintService, no en BD).
- Empleado con `eliminado=true` conserva su historial de `consumo`.

## 3. Diagrama de clases (capa de servicio)

```mermaid
classDiagram
    class ScanService {
        +scan(ScanRequest) ScanResponse
        -resolveMealForScan(emp, time, date) MealSelection
    }
    class BiometricMatcher {
        <<interface>>
        +enroll(fpId, empId, template)
        +remove(fpId)
        +rebuildIndex()
        +identify(template) MatchResult
    }
    class ZkBiometricMatcher
    class DeviceService {
        +connect(req) DeviceConnectResponse
        +validateSession(token) Device
    }
    class SyncService {
        +sync(batch) SyncBatchResponse
    }
    class AuthService
    class EmployeeService
    class FingerprintService
    class CatalogService
    class ReportService
    class ExportService
    class AuditService

    BiometricMatcher <|.. ZkBiometricMatcher
    ScanService --> BiometricMatcher
    ScanService --> DeviceService
    SyncService --> ScanService
    FingerprintService --> BiometricMatcher
    ZkBiometricMatcher --> ZkfpSdk : JNA
```

## 4. Estrategia biométrica (ZK9500)

| Aspecto | Decisión |
|--------|----------|
| Captura | Agente **ZKFinger WebAPI** en el dispositivo, expuesto por WebSocket local. El navegador obtiene la **plantilla** (no la imagen). |
| Almacenamiento | Solo `plantilla` (BLOB) + copia base64. **Nunca** se guardan imágenes de huella. Máx. 3 por empleado. |
| Identificación | **1:N en el servidor** con `libzkfp` (`ZKFPM_DBIdentify`) vía **JNA**. Índice en memoria de todas las plantillas activas, reconstruible. |
| Umbral | Configurable (`app.biometric.match-threshold`, por defecto 70). |
| Intercambiable | Interfaz `BiometricMatcher`: `zk` (SDK real) o `sim` (pruebas sin hardware). |
| Motor vs lector | El **matching 1:N no requiere lector conectado al servidor**: cargada la DLL, `ZKFPM_DBInit`/`DBIdentify` operan sin hardware. `engineReady` (validar) y `readerConnected` (capturar/enrolar desde la web) son estados independientes en `GET /api/fingerprints/biometric-status`. |
| Resiliencia | Si el SDK nativo no carga, el servicio degrada a "HUELLA NO ENCONTRADA" sin caerse. Si una plantilla no se puede descifrar (clave cambiada), se **omite** esa huella y se refleja como `indexMatchesDb=false` en `biometric-status`, sin tumbar la petición. |

## 5. Estrategia offline

1. El dispositivo opera normalmente sin conexión: captura la plantilla y la **encola en IndexedDB** con `clientUuid` + `consumedAt`.
2. Los registros en cola **no se pueden modificar**.
3. Al volver la conexión (evento `online` o sondeo cada 15 s), se envían en lote a `POST /api/scan/sync`.
4. El backend resuelve identidad y reglas en el momento de sincronizar y responde por registro.
5. **Antiduplicados:** `uuid_cliente` único + restricción `(empleado, comida, día)`. Reenviar un lote es seguro (idempotente).

> Compromiso de diseño: como la identificación 1:N ocurre en el servidor, en modo
> offline la pantalla confirma "REGISTRO EN COLA" (sin nombre) y la identidad se
> resuelve al sincronizar. Alternativa futura: delegar el `Identify` local al agente
> ZKFinger con las plantillas precargadas para mostrar el nombre también offline.

## 6. Seguridad

- **JWT** (access 30 min + refresh 7 días con rotación y revocación en `sesion_inicio`).
- **BCrypt** para contraseñas.
- **Plantillas biométricas cifradas** con AES-256/GCM (integridad verificada). La clave se persiste protegida con **DPAPI** en `C:\ProgramData\ControlEatFood\` (fuera del repo y de la carpeta que se borra al desinstalar) y se reutiliza en reinstalaciones para no invalidar las huellas ya cifradas.
- **Roles** `ADMIN`/`CATERING` con `@PreAuthorize` por endpoint.
- **Fuerza bruta:** bloqueo temporal tras N intentos (`app.security.brute-force`).
- **Dispositivos simultáneos:** máx. 2 por restaurante (token de sesión de dispositivo).
- **CORS** restringido por configuración; API stateless (sin cookies → superficie CSRF mínima).
- **Auditoría** de cambios con usuario, IP, user-agent, valor anterior/nuevo.

## 7. Despliegue

```
            ┌──────────── Nginx / reverse proxy (TLS) ────────────┐
            │  /            → frontend (PWA estática)              │
            │  /api, /swagger → backend Spring Boot (3000)         │
            └──────────────────────┬──────────────────────────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │  Spring Boot (JAR / Docker)  │
                    └──────────────┬──────────────┘
                                   │
                             MySQL 8 (con respaldos)
```

- **Backend:** `mvn clean package` → JAR ejecutable; o contenedor Docker (`eclipse-temurin:21-jre`). Montar `native/` con las DLL/.so del SDK.
- **Frontend:** `npm run build` → `dist/` servido por Nginx/CDN (PWA con service worker).
- **Dispositivos:** navegador en modo kiosco apuntando a `/kiosk`; agente ZKFinger instalado.
- **Variables sensibles** (JWT_SECRET, credenciales DB) por entorno/secrets, nunca en el repo.

## 8. Respaldo y recuperación

- **MySQL:** `mysqldump` diario + binlog para PITR (point-in-time recovery).
- **Retención:** 30 días en caliente, copias mensuales en almacenamiento externo.
- **Pruebas de restauración** periódicas en entorno de staging.
- **Plantillas biométricas:** incluidas en el respaldo de la BD (BLOB); el índice en memoria se reconstruye al iniciar (`rebuildIndex`).
- **RPO** objetivo ≤ 24 h (≤ minutos con binlog); **RTO** ≤ 1 h.

## 9. Escalabilidad y mantenimiento

- Backend **stateless** → escalado horizontal tras balanceador. El índice biométrico es local a cada instancia; para muchos nodos, centralizar el matching en un servicio dedicado o usar identificación en el borde.
- **Connection pooling** (HikariCP) e índices en columnas de reporte (`fecha_negocio`, `restaurante_id`).
- Reportes pesados → vistas (`v_consumo_diario`) y posibilidad de réplica de lectura.
- **Migraciones versionadas** con Flyway. **Observabilidad** con Spring Actuator.
