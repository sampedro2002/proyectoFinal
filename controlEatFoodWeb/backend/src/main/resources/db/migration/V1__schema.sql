-- ============================================================================
-- Control de Consumo de Alimentos por Huella Digital (ZK9500)
-- Esquema relacional MySQL — estado final consolidado (reemplaza V1..V11)
--
-- Todas las tablas usan CREATE TABLE IF NOT EXISTS para poder correr esta
-- migración contra una base de datos ya creada (por ejemplo, en una máquina
-- nueva) sin que falle por "tabla ya existe". Los índices y UNIQUE KEY van
-- definidos dentro del propio CREATE TABLE: si la tabla ya existe, MySQL
-- salta la sentencia completa (índices incluidos), así que no hace falta
-- una guarda aparte para cada índice.
--
-- Nota: si una base de datos ya tiene estas tablas pero con una estructura
-- distinta (por ejemplo, restos de una versión anterior con `catering` o
-- `position`), IF NOT EXISTS NO actualiza columnas existentes. Para ese caso
-- hay que partir de una base de datos vacía.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- SEGURIDAD: roles y usuarios del sistema (Administrador, Catering)
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
-- EMPLEADOS
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS employee (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    identity_card   VARCHAR(20) NOT NULL UNIQUE,
    full_name       VARCHAR(160) NOT NULL,
    public_code     VARCHAR(12) NOT NULL UNIQUE,
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
    meal_name       VARCHAR(30),
    observation     VARCHAR(500),
    client_uuid     VARCHAR(36) NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_consumption_client_uuid (client_uuid),
    KEY idx_consumption_date (business_date),
    KEY idx_consumption_restaurant_date (restaurant_id, business_date),
    KEY idx_consumption_employee_date (employee_id, business_date),
    FOREIGN KEY (employee_id) REFERENCES employee(id),
    FOREIGN KEY (restaurant_id) REFERENCES restaurant(id),
    FOREIGN KEY (device_id) REFERENCES device(id)
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
-- CONFIGURACIÓN (tabla histórica; la app hoy lee configuración desde
-- application.yml / AppProperties, no desde aquí)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_config (
    config_key   VARCHAR(80) PRIMARY KEY,
    config_value VARCHAR(400) NOT NULL,
    description  VARCHAR(200),
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
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
