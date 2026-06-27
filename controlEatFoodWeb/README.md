# Control de Consumo de Alimentos por Huella Digital (ZK9500)

Sistema web para registrar el consumo de alimentos de empleados mediante
**identificación biométrica 1:N por huella digital** (lector ZKTeco **ZK9500**).
El empleado solo coloca el dedo en el lector del punto de catering; el sistema lo
identifica automáticamente y registra el consumo según horarios, permisos y reglas
de negocio, con soporte **offline**, **auditoría** completa y **reportes**.

---

## 🧱 Arquitectura (resumen)

```
┌─────────────────────────┐        HTTPS/REST + JWT        ┌──────────────────────────┐
│  Frontend PWA (React)   │  ───────────────────────────► │  Backend Spring Boot 3    │
│  - Panel admin/supervisor│                               │  - API REST + Seguridad   │
│  - Pantalla Catering     │ ◄───────────────────────────  │  - Motor biométrico 1:N   │
│  - Modo kiosco + offline │                               │  - Auditoría / Reportes   │
└───────────┬─────────────┘                               └────────────┬─────────────┘
            │ WebSocket local                                          │ JPA/Hibernate
            ▼                                                          ▼
   ┌──────────────────┐                                        ┌──────────────┐
   │ ZKFinger WebAPI  │  (agente local en el dispositivo)      │  MySQL  │
   │  + Lector ZK9500 │                                        └──────────────┘
   └──────────────────┘
```

Documentación detallada en [`docs/`](docs/):
- [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md) — arquitectura, modelo ER, diagrama de clases, estrategias biométrica/offline, despliegue, respaldo y escalabilidad.
- [`docs/API.md`](docs/API.md) — API REST documentada.
- [`docs/CASOS_DE_USO_HISTORIAS.md`](docs/CASOS_DE_USO_HISTORIAS.md) — casos de uso e historias de usuario.

---

## 📁 Estructura del proyecto

```
controlEatFood/
├── backend/                 # Spring Boot 3 (Java 21, Maven)
│   ├── src/main/java/com/eatfood/control/
│   │   ├── config/          # Seguridad, CORS, OpenAPI, auditoría, datos iniciales
│   │   ├── domain/          # Entidades JPA
│   │   ├── repository/      # Repositorios Spring Data
│   │   ├── dto/             # Objetos de transferencia
│   │   ├── service/         # Lógica de negocio
│   │   ├── biometric/       # Integración SDK ZKFinger (JNA) + matcher 1:N
│   │   ├── security/        # JWT, filtros, UserDetails
│   │   ├── exception/       # Manejo global de errores
│   │   └── web/             # Controladores REST
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/    # Scripts SQL (Flyway): esquema + seed
│   └── native/              # DLL/.so del SDK ZK9500 (no incluidas)
└── frontend/                # React + Vite (PWA)
    └── src/
        ├── pages/           # Login, Dashboard, Empleados, Reportes, Kiosco...
        ├── biometric/       # Cliente ZKFinger WebAPI (WebSocket)
        ├── offline/         # Cola IndexedDB + sincronización + sonidos
        ├── auth/ api/ components/
```

---

## ✅ Requisitos previos

| Componente | Versión | Notas |
|-----------|---------|-------|
| JDK | **21** | requerido por el backend |
| Maven | 3.9+ | build del backend |
| MySQL | 8.0+ / 9.x | base de datos (Ej. Ejecutándose en Docker como `mi-mysql`) |
| Node.js | 18+ | build del frontend |
| SDK ZKFinger | v10 | DLL/.so del ZK9500 (solo para biometría real) |

---

## 🚀 Puesta en marcha

### 1. Base de datos (MySQL)

El sistema está configurado para utilizar **MySQL** (por ejemplo, mediante una instancia en Docker `mi-mysql` con puerto `3306`). 

Para iniciar con una base de datos limpia:
- Asegúrate de que las bases de datos `control_eat_food` y `registerfoot` estén creadas.
- Flyway creará el esquema y cargará los datos iniciales automáticamente al arrancar el backend.

*Notas de migración:*
- `V1__schema.sql`: esquema base. El `DELIMITER $$ + TRIGGER` original fue removido (Flyway no soporta `DELIMITER` vía JDBC); el límite de 3 huellas activas por empleado lo impone `FingerprintService.MAX_FINGERPRINTS`.
- `V3__fix_fingerprint_schema.sql`: removida la instrucción `DROP COLUMN IF EXISTS` (no soportada por MySQL 9.x).
- `V4__remove_plates.sql`: elimina las columnas `allowed_plates`/`default_plates`/`plates` (ya no se usan; un consumo equivale a 1 plato).
- `V5__fix_views_after_plates_removal.sql`: recrea las vistas `v_daily_consumption` y `v_employee_effective_config` sin las columnas eliminadas en V4.
- `V6__rename_catering_users.sql`: renombra los usuarios de catering de `catering<id>` a `catering<Nombre>` (p. ej. `cateringNorte`, `cateringCentro`, `cateringSur`).

> Si modificas una migración ya aplicada, ejecuta `flyway repair` para alinear el checksum en `flyway_schema_history`.

### 2. Backend

```bash
cd backend
# Variables (opcionales; ver application.yml para valores por defecto)
export DB_URL=jdbc:mysql://localhost:3306/control_eat_food
export DB_USER=root
export DB_PASSWORD=BN2002sg
export JWT_SECRET=<clave-base64-256bits>
export BIOMETRIC_PROVIDER=zk        # 'zk' (SDK real) | 'sim' (sin hardware)
export ZK_NATIVE_PATH=./native      # ruta de las DLL del SDK ZK9500

mvn spring-boot:run
```

- API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

<details>
<summary><b>Variables de entorno del backend</b></summary>

| Variable | Default | Descripción |
|----------|---------|-------------|
| `DB_URL` | `jdbc:mysql://localhost:3306/control_eat_food?...` | URL JDBC de MySQL |
| `DB_USER` | `admin` | Usuario de BD |
| `DB_PASSWORD` | — | Contraseña de BD (definir siempre en producción) |
| `JWT_SECRET` | — | Clave Base64 (mín. 256 bits). **Definir siempre en producción.** |
| `BIOMETRIC_PROVIDER` | `zk` | `zk` (SDK real) \| `sim` (sin hardware) |
| `ZK_NATIVE_PATH` | `./native` | Ruta de las DLL/.so del SDK ZK9500 |
| `BIOMETRIC_ENCRYPTION_KEY` | — | Clave AES-128 para cifrar plantillas (mín. 16 bytes). **Definir siempre en producción.** |
| `CORS_ORIGINS` | `http://localhost:5173,...` | Orígenes CORS permitidos (separados por coma) |

</details>

> **Liberar puerto 8080 (en Windows):**
> Si el puerto 8080 está ocupado, puedes forzar el cierre del proceso fantasma desde PowerShell como Administrador:
> ```powershell
> Stop-Process -Id (Get-NetTCPConnection -LocalPort 8080).OwningProcess -Force
> ```

### 3. Frontend

```bash
cd frontend
cp .env.example .env     # ajusta VITE_ZKFINGER_WS
npm install
npm run dev              # http://localhost:5173
```

### 4. Credenciales iniciales

| Rol | Usuario | Contraseña |
|-----|---------|-----------|
| Administrador | `admin` | `Admin123*` |
| Catering (1 por catering) | `cateringNorte`, `cateringCentro`, `cateringSur` | `catering123` |

> Las contraseñas se cifran con BCrypt al primer arranque (`DataInitializer`).
> **Cámbialas en producción.**

---

## 🔐 Roles y permisos

- **Administrador:** gestiona empleados, huellas, cargos, caterings, horarios, permisos; consulta auditoría; genera y exporta reportes; **registra consumos manuales** (empleado o persona externa) sin validar horario/permiso/duplicado.
- **Supervisor:** solo consulta (reportes, consumos, estadísticas).
- **Catering:** registra consumos desde su dispositivo; ve solo lo propio.

---

## 🖐 Flujo biométrico (ZK9500)

1. El **agente ZKFinger WebAPI** corre en el dispositivo de catering y expone el lector USB por WebSocket local.
2. El frontend captura la **plantilla** (template) y la envía al backend.
3. El backend ejecuta **identificación 1:N** con el SDK nativo (`libzkfp` vía JNA) y resuelve al empleado.
4. Se aplican las reglas de negocio y se registra el consumo.

Coloca las DLL del SDK en `backend/native/` (ver `backend/native/README.md`).

---
 
 ## 🚀 Funcionalidades Avanzadas
 
 - **Gestión de Fallos**: El sistema registra y audita los escaneos fallidos (`FailedScan`) para analizar problemas de lectura o intentos no autorizados.
 - **Registro Manual de Consumos**: El administrador puede registrar consumos sin huella desde el panel web o la app móvil, eligiendo empleado, catering y tipo de comida (Almuerzo/Merienda). No se validan horario, permiso ni duplicados: pensado para correcciones.
 - **Persona Externa**: El administrador puede registrar consumos para personas no empleadas (visitantes, contratistas) sin necesidad de crearlas previamente. El sistema crea un empleado temporal `INACTIVE` reutilizable por cédula, de modo que el consumo aparece en el feed del kiosk y en reportes, pero no contamina la gestión de empleados activos.
 - **Control de Dispositivos**: Gestión centralizada de los puntos de catering y sus dispositivos asociados.
 - **Exportación de Datos**: Generación de reportes detallados exportables (CSV/Excel/PDF) para análisis externo, con escapado anti inyección de fórmulas en CSV.
 
 ---
 
 ## 🛡️ Seguridad y Privacidad
 
 - **Cifrado de Plantillas**: Las huellas digitales no se guardan en texto plano; se utiliza **cifrado AES-128/CBC** para proteger las plantillas biométricas en la base de datos. La clave se configura vía `BIOMETRIC_ENCRYPTION_KEY` (no se deja clave por defecto en producción).
 - **Seguridad JWT**: Acceso protegido mediante tokens JWT con roles definidos. Anti-fuerza-bruta con bloqueo temporal de cuenta tras 5 intentos fallidos (persistente en transacción independiente para no revertirse con el rollback del login fallido).
 - **Auditoría Completa**: Cada acción crítica es registrada en un log de auditoría (`AuditLog`) con usuario, fecha y acción realizada.
 - **Manejo de Errores**: El backend no expone detalles internos (SQL/stack) al cliente; los errores genéricos se loguean internamente y devuelven un mensaje neutro.
 
 ---
 
 ## 📶 Modo offline

 - Si se pierde la conexión, el dispositivo guarda los escaneos en una **cola local (IndexedDB)** con un `clientUuid` único.
 - Al recuperar la conexión, se **sincronizan automáticamente** vía `POST /api/scan/sync`.
 - La **idempotencia** (por `clientUuid`) y el índice único (empleado + comida + día) **evitan duplicados**. Bajo concurrencia, se captura `DataIntegrityViolationException` y se devuelve el consumo existente en lugar de un 500.
 - Las capturas biométricas en modo continuo se **serializan** (cola FIFO) para evitar registros dobles por plantillas llegadas casi simultáneas.

 ---

## 🧪 Estado de validación

- **Frontend:** `npm run build` ✔ (compila correctamente).
- **Backend:** `mvn clean compile` ✔ (JDK 21 + Maven 3.9). BUILD SUCCESS, 73 fuentes.
- **App Móvil:** `./gradlew :app:compileDebugKotlin` ✔ (BUILD SUCCESSFUL).

---

## 📱 App Móvil Android

Existe una aplicación móvil complementaria en **[`../controlEatFoodMovil`](../controlEatFoodMovil)** (Kotlin + Jetpack Compose) que consume la misma API REST del backend. Permite:

- Login con JWT (mismas credenciales que el panel web).
- Dashboard con estadísticas del día.
- CRUD de empleados, cargos, caterings, horarios y huellas (con lector ZK9500 vía USB OTG).
- **Almuerzos Extra**: registro manual de consumos para empleados existentes o **personas externas** (visitantes/contratistas), con selector de catering y tipo de comida.
- Reportes y auditoría.
- Modo kiosco con lector biométrico USB OTG y cola offline (Room).

Ver su README en [`../controlEatFoodMovil/README.md`](../controlEatFoodMovil/README.md) para puesta en marcha.

---

## 📝 Endpoints de registro manual

| Función | Método | Endpoint | Body | Autorización |
|---------|--------|----------|------|--------------|
| Registro manual (empleado existente) | POST | `/api/manual-consumptions` | `{ employeeId, mealTypeCode, cateringId }` | `ADMIN` |
| Registro de persona externa | POST | `/api/manual-consumptions/external` | `{ identityCard, fullName, mealTypeCode, cateringId }` | `ADMIN` |

Ambos endpoints registran el consumo con `businessDate = hoy (America/Guayaquil)`, `offline=false`, `syncStatus=SYNCED`, y un `clientUuid` aleatorio. El consumo aparece en el feed del kiosk del catering elegido y en los reportes. No validan horario, permiso ni duplicado (corrección forzada por admin).
