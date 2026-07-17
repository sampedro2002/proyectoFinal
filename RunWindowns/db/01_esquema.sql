-- ============================================================================
-- Control de Alimentos Club Castillo Amaguaña — Esquema MySQL (estado actual)
--
-- Este script lo ejecuta Inicio.ps1 (carpeta RunWindowns\db) SOLO cuando la
-- base de datos aún no tiene la estructura (se detecta por la tabla employee).
-- Es idéntico en estructura a la migración Flyway V1__schema.sql del backend,
-- que queda como respaldo (baseline-on-migrate): si este script no se
-- ejecuta, Flyway crea el esquema igual al arrancar la aplicación.
-- Si cambias la estructura aquí, cámbiala también en V1 (y viceversa).
--
-- Todas las tablas usan CREATE TABLE IF NOT EXISTS para poder correrlo contra
-- una base parcialmente creada sin fallar por "tabla ya existe". Los índices
-- y UNIQUE KEY van dentro del propio CREATE TABLE.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- SEGURIDAD: roles y usuarios del sistema (Administrador, Restaurante)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS roles (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(40) NOT NULL UNIQUE,         -- ADMIN, CATERING
    description VARCHAR(120)
);

-- ----------------------------------------------------------------------------
-- RESTAURANTES
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS restaurant (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(120) NOT NULL UNIQUE,
    location       VARCHAR(160),
    representative VARCHAR(120),
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    max_devices    INT NOT NULL DEFAULT 2 CHECK (max_devices > 0),
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS app_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(60) NOT NULL UNIQUE,
    password_hash   VARCHAR(120) NOT NULL,
    full_name       VARCHAR(120) NOT NULL,
    email           VARCHAR(120),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until    DATETIME,
    restaurant_id   BIGINT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant(id)
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles(id)
);

-- ----------------------------------------------------------------------------
-- EMPLEADOS (el identificador es el id autoincremental; no hay código público)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS employee (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    identity_card   VARCHAR(20) NOT NULL UNIQUE,
    full_name       VARCHAR(160) NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    observation     VARCHAR(500),
    allows_lunch    BOOLEAN NOT NULL DEFAULT TRUE,
    allows_snack    BOOLEAN NOT NULL DEFAULT FALSE,
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_employee_status_not_deleted (status, deleted)
);

-- ----------------------------------------------------------------------------
-- HUELLAS
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fingerprint (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    employee_id   BIGINT NOT NULL,
    finger_index  SMALLINT NOT NULL CHECK (finger_index BETWEEN 0 AND 9),
    template      BLOB NOT NULL,
    enrolled_by   BIGINT,
    enrolled_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE KEY uq_fingerprint_employee_finger (employee_id, finger_index),
    KEY idx_fingerprint_employee (employee_id),
    FOREIGN KEY (employee_id) REFERENCES employee(id) ON DELETE CASCADE,
    FOREIGN KEY (enrolled_by) REFERENCES app_user(id)
);

-- Nota: el límite de 3 huellas activas por empleado se impone en FingerprintService
-- (MAX_FINGERPRINTS = 3), no en la base de datos.

-- ----------------------------------------------------------------------------
-- DISPOSITIVOS
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurant_id BIGINT NOT NULL,
    device_uid    VARCHAR(80) NOT NULL,
    name          VARCHAR(120),
    last_seen     DATETIME,
    session_token VARCHAR(120),
    connected     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_device_restaurant_uid (restaurant_id, device_uid),
    KEY idx_device_restaurant_connected (restaurant_id, connected),
    FOREIGN KEY (restaurant_id) REFERENCES restaurant(id)
);

-- ----------------------------------------------------------------------------
-- HORARIO (único, aplica a todas las comidas del día)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schedule (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    start_time TIME NOT NULL,
    end_time   TIME NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CHECK (end_time > start_time)
);

-- ----------------------------------------------------------------------------
-- REGISTRO DE CONSUMO
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS consumption (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    employee_id     BIGINT NOT NULL,
    restaurant_id   BIGINT NOT NULL,
    device_id       BIGINT,
    consumed_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    business_date   DATE NOT NULL,
    offline         BOOLEAN NOT NULL DEFAULT FALSE,
    sync_status     VARCHAR(12) NOT NULL DEFAULT 'SYNCED' CHECK (sync_status IN ('SYNCED','PENDING','CONFLICT')),
    -- method: origen del registro. FINGERPRINT = escaneo de huella del propio
    -- empleado; MANUAL = registro manual "retira por otro" (proxy_employee_id
    -- indica quién retira); EXTERNAL = persona externa creada al vuelo.
    method          VARCHAR(12) NOT NULL DEFAULT 'FINGERPRINT'
                        CHECK (method IN ('FINGERPRINT','MANUAL','EXTERNAL')),
    proxy_employee_id BIGINT,               -- empleado que retira (solo method='MANUAL')
    meal_name       VARCHAR(30),          -- 'Almuerzo' (1er plato) o 'Merienda' (2º plato)
    observation     VARCHAR(500),
    client_uuid     VARCHAR(36) NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_consumption_client_uuid (client_uuid),
    KEY idx_consumption_date (business_date),
    KEY idx_consumption_restaurant_date (restaurant_id, business_date),
    KEY idx_consumption_employee_date (employee_id, business_date),
    KEY idx_consumption_method (method),
    KEY idx_consumption_proxy (proxy_employee_id),
    FOREIGN KEY (employee_id) REFERENCES employee(id),
    FOREIGN KEY (restaurant_id) REFERENCES restaurant(id),
    FOREIGN KEY (device_id) REFERENCES device(id),
    FOREIGN KEY (proxy_employee_id) REFERENCES employee(id) ON DELETE SET NULL
);

-- Nota: no hay índice único por (empleado, día): el tope de comidas por día
-- lo controla ScanService, y la idempotencia por reintentos la da client_uuid.

-- ----------------------------------------------------------------------------
-- INTENTOS FALLIDOS
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS failed_scan (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurant_id BIGINT,
    device_id     BIGINT,
    employee_id   BIGINT,
    reason        VARCHAR(30) NOT NULL,
    occurred_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_failed_scan_date (occurred_at),
    FOREIGN KEY (restaurant_id) REFERENCES restaurant(id),
    FOREIGN KEY (device_id) REFERENCES device(id),
    FOREIGN KEY (employee_id) REFERENCES employee(id)
);

-- ----------------------------------------------------------------------------
-- AUDITORÍA
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(60),
    entity_name  VARCHAR(60) NOT NULL,
    entity_id    VARCHAR(40),
    action       VARCHAR(20) NOT NULL,
    old_value    TEXT,
    new_value    TEXT,
    ip_address   VARCHAR(60),
    device_info  VARCHAR(200),
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_audit_entity (entity_name, entity_id),
    KEY idx_audit_date (created_at)
);

-- ----------------------------------------------------------------------------
-- SESIONES
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS login_session (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    refresh_token VARCHAR(200) NOT NULL,
    ip_address    VARCHAR(60),
    device_info   VARCHAR(200),
    issued_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at    DATETIME NOT NULL,
    revoked       BOOLEAN NOT NULL DEFAULT FALSE,
    KEY idx_session_user (user_id),
    FOREIGN KEY (user_id) REFERENCES app_user(id)
);

-- ----------------------------------------------------------------------------
-- VISTAS (CREATE OR REPLACE: siempre queda con la definición más reciente)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_daily_consumption AS
SELECT c.business_date,
       c.restaurant_id,
       'GENERAL' AS meal_code,
       COUNT(*)  AS total_records
FROM consumption c
GROUP BY c.business_date, c.restaurant_id;

CREATE OR REPLACE VIEW v_employee_effective_config AS
SELECT e.id AS employee_id,
       e.full_name,
       e.allows_lunch,
       e.allows_snack AS effective_snack
FROM employee e
WHERE e.deleted = FALSE;
