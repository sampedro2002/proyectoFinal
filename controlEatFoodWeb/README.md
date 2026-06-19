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
   │ ZKFinger WebAPI  │  (agente local en el dispositivo)      │  PostgreSQL  │
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

*Nota:* Se corrigió un error de sintaxis en `V3__fix_fingerprint_schema.sql` removiendo la instrucción incompatible `DROP COLUMN IF EXISTS` (no soportada por MySQL 9.x), haciéndola 100% compatible.

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

> **Liberar puerto 8080 (en Windows):**
> Si el puerto 8080 está ocupado, puedes forzar el cierre del proceso fantasma desde PowerShell como Administrador:
> ```powershell
> Stop-Process -Id (Get-NetTCPConnection -LocalPort 8080).OwningProcess -Force
> ```

> **Sin hardware ZK9500:** arranca con `BIOMETRIC_PROVIDER=sim` y en el frontend
> usa `VITE_BIOMETRIC_SIM=true` para probar todo el flujo de extremo a extremo.

### 3. Frontend

```bash
cd frontend
cp .env.example .env     # ajusta VITE_ZKFINGER_WS / VITE_BIOMETRIC_SIM
npm install
npm run dev              # http://localhost:5173
```

### 4. Credenciales iniciales

| Rol | Usuario | Contraseña |
|-----|---------|-----------|
| Administrador | `admin` | `Admin123*` |
| Catering (1 por catering) | `catering1`, `catering2`, `catering3` | `catering123` |

> Las contraseñas se cifran con BCrypt al primer arranque (`DataInitializer`).
> **Cámbialas en producción.**

---

## 🔐 Roles y permisos

- **Administrador:** gestiona empleados, huellas, cargos, caterings, horarios, platos, permisos; consulta auditoría; genera y exporta reportes.
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

## 📶 Modo offline

- Si se pierde la conexión, el dispositivo guarda los escaneos en una **cola local (IndexedDB)** con un `clientUuid` único.
- Al recuperar la conexión, se **sincronizan automáticamente** vía `POST /api/scan/sync`.
- La **idempotencia** (por `clientUuid`) y el índice único (empleado + comida + día) **evitan duplicados**.

---

## 🧪 Estado de validación

- **Frontend:** `npm run build` ✔ (compila correctamente).
- **Backend:** revisado; requiere JDK 21 + Maven para compilar/ejecutar (no disponibles en el equipo de generación). Usa `mvn clean verify`.
