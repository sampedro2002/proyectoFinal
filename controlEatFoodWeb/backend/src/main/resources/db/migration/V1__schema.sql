-- ============================================================================
-- Control de Consumo de Alimentos por Huella Digital (ZK9500)
-- Esquema relacional MySQL
-- ============================================================================

-- ----------------------------------------------------------------------------
-- SEGURIDAD: roles y usuarios del sistema (Administrador, Supervisor, Catering)
-- ----------------------------------------------------------------------------
CREATE TABLE roles (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(40) NOT NULL UNIQUE,         -- ADMIN, SUPERVISOR, CATERING
    description VARCHAR(120)
);

CREATE TABLE app_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(60) NOT NULL UNIQUE,
    password_hash   VARCHAR(120) NOT NULL,
    full_name       VARCHAR(120) NOT NULL,
    email           VARCHAR(120),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until    DATETIME,
    catering_id     BIGINT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles(id)
);

-- ----------------------------------------------------------------------------
-- CARGOS
-- ----------------------------------------------------------------------------
CREATE TABLE `position` (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(120) NOT NULL UNIQUE,
    default_plates  INT NOT NULL DEFAULT 1 CHECK (default_plates >= 0),
    allows_snack    BOOLEAN NOT NULL DEFAULT FALSE,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------------------
-- EMPLEADOS
-- ----------------------------------------------------------------------------
CREATE TABLE employee (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    identity_card     VARCHAR(20) NOT NULL UNIQUE,
    full_name         VARCHAR(160) NOT NULL,
    position_id       BIGINT,
    status            VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    allowed_plates    INT CHECK (allowed_plates >= 0),
    allows_lunch      BOOLEAN NOT NULL DEFAULT TRUE,
    allows_snack      BOOLEAN NOT NULL DEFAULT FALSE,
    deleted           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (position_id) REFERENCES `position`(id)
);

CREATE INDEX idx_employee_status_not_deleted ON employee(status, deleted);
CREATE INDEX idx_employee_position ON employee(position_id);

-- ----------------------------------------------------------------------------
-- HUELLAS
-- ----------------------------------------------------------------------------
CREATE TABLE fingerprint (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    employee_id   BIGINT NOT NULL,
    finger_index  SMALLINT NOT NULL CHECK (finger_index BETWEEN 0 AND 9),
    template      BLOB NOT NULL,
    template_b64  TEXT NOT NULL,
    enrolled_by   BIGINT,
    enrolled_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (employee_id, finger_index),
    FOREIGN KEY (employee_id) REFERENCES employee(id) ON DELETE CASCADE,
    FOREIGN KEY (enrolled_by) REFERENCES app_user(id)
);
CREATE INDEX idx_fingerprint_employee ON fingerprint(employee_id);

DELIMITER $$
CREATE TRIGGER check_max_fingerprints
BEFORE INSERT ON fingerprint
FOR EACH ROW
BEGIN
    DECLARE cnt INT;
    SELECT COUNT(*) INTO cnt FROM fingerprint WHERE employee_id = NEW.employee_id AND active = TRUE;
    IF cnt >= 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Un empleado no puede tener mas de 3 huellas activas';
    END IF;
END$$
DELIMITER ;

-- ----------------------------------------------------------------------------
-- CATERINGS
-- ----------------------------------------------------------------------------
CREATE TABLE catering (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(120) NOT NULL UNIQUE,
    location    VARCHAR(160),
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    max_devices INT NOT NULL DEFAULT 2 CHECK (max_devices > 0),
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

ALTER TABLE app_user ADD CONSTRAINT fk_user_catering FOREIGN KEY (catering_id) REFERENCES catering(id);

-- ----------------------------------------------------------------------------
-- DISPOSITIVOS
-- ----------------------------------------------------------------------------
CREATE TABLE device (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    catering_id   BIGINT NOT NULL,
    device_uid    VARCHAR(80) NOT NULL,
    name          VARCHAR(120),
    last_seen     DATETIME,
    session_token VARCHAR(120),
    connected     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (catering_id, device_uid),
    FOREIGN KEY (catering_id) REFERENCES catering(id)
);
CREATE INDEX idx_device_catering_connected ON device(catering_id, connected);

-- ----------------------------------------------------------------------------
-- TIPOS DE COMIDA
-- ----------------------------------------------------------------------------
CREATE TABLE meal_type (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(30) NOT NULL UNIQUE,
    name        VARCHAR(60) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order  INT NOT NULL DEFAULT 0
);

-- ----------------------------------------------------------------------------
-- HORARIOS
-- ----------------------------------------------------------------------------
CREATE TABLE schedule (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    meal_type_id BIGINT NOT NULL,
    start_time   TIME NOT NULL,
    end_time     TIME NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CHECK (end_time > start_time),
    FOREIGN KEY (meal_type_id) REFERENCES meal_type(id)
);
CREATE UNIQUE INDEX uq_schedule_meal ON schedule(meal_type_id);

-- ----------------------------------------------------------------------------
-- REGISTRO DE CONSUMO
-- ----------------------------------------------------------------------------
CREATE TABLE consumption (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    employee_id     BIGINT NOT NULL,
    catering_id     BIGINT NOT NULL,
    meal_type_id    BIGINT NOT NULL,
    device_id       BIGINT,
    plates          INT NOT NULL CHECK (plates > 0),
    consumed_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    business_date   DATE NOT NULL,
    offline         BOOLEAN NOT NULL DEFAULT FALSE,
    sync_status     VARCHAR(12) NOT NULL DEFAULT 'SYNCED' CHECK (sync_status IN ('SYNCED','PENDING','CONFLICT')),
    client_uuid     VARCHAR(36) NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (employee_id) REFERENCES employee(id),
    FOREIGN KEY (catering_id) REFERENCES catering(id),
    FOREIGN KEY (meal_type_id) REFERENCES meal_type(id),
    FOREIGN KEY (device_id) REFERENCES device(id)
);

CREATE UNIQUE INDEX uq_consumption_per_day ON consumption(employee_id, meal_type_id, business_date);
CREATE UNIQUE INDEX uq_consumption_client_uuid ON consumption(client_uuid);
CREATE INDEX idx_consumption_date ON consumption(business_date);
CREATE INDEX idx_consumption_catering_date ON consumption(catering_id, business_date);
CREATE INDEX idx_consumption_employee ON consumption(employee_id);

-- ----------------------------------------------------------------------------
-- INTENTOS FALLIDOS
-- ----------------------------------------------------------------------------
CREATE TABLE failed_scan (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    catering_id  BIGINT,
    device_id    BIGINT,
    reason       VARCHAR(30) NOT NULL,
    meal_type_id BIGINT,
    employee_id  BIGINT,
    occurred_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (catering_id) REFERENCES catering(id),
    FOREIGN KEY (device_id) REFERENCES device(id),
    FOREIGN KEY (meal_type_id) REFERENCES meal_type(id),
    FOREIGN KEY (employee_id) REFERENCES employee(id)
);
CREATE INDEX idx_failed_scan_date ON failed_scan(occurred_at);

-- ----------------------------------------------------------------------------
-- AUDITORÍA
-- ----------------------------------------------------------------------------
CREATE TABLE audit_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    username     VARCHAR(60),
    entity_name  VARCHAR(60) NOT NULL,
    entity_id    VARCHAR(40),
    action       VARCHAR(20) NOT NULL,
    old_value    TEXT,
    new_value    TEXT,
    ip_address   VARCHAR(60),
    device_info  VARCHAR(200),
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_entity ON audit_log(entity_name, entity_id);
CREATE INDEX idx_audit_date ON audit_log(created_at);

-- ----------------------------------------------------------------------------
-- SESIONES
-- ----------------------------------------------------------------------------
CREATE TABLE login_session (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    refresh_token VARCHAR(200) NOT NULL,
    ip_address   VARCHAR(60),
    device_info  VARCHAR(200),
    issued_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at   DATETIME NOT NULL,
    revoked      BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE INDEX idx_session_user ON login_session(user_id);

-- ----------------------------------------------------------------------------
-- CONFIGURACIÓN
-- ----------------------------------------------------------------------------
CREATE TABLE app_config (
    config_key   VARCHAR(80) PRIMARY KEY,
    config_value VARCHAR(400) NOT NULL,
    description  VARCHAR(200),
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------------------
-- VISTAS
-- ----------------------------------------------------------------------------
CREATE VIEW v_daily_consumption AS
SELECT c.business_date,
       c.catering_id,
       mt.code        AS meal_code,
       COUNT(*)       AS total_records,
       SUM(c.plates)  AS total_plates
FROM consumption c
JOIN meal_type mt ON mt.id = c.meal_type_id
GROUP BY c.business_date, c.catering_id, mt.code;

CREATE VIEW v_employee_effective_config AS
SELECT e.id AS employee_id,
       e.full_name,
       COALESCE(e.allowed_plates, p.default_plates, 1) AS effective_plates,
       e.allows_lunch,
       (e.allows_snack OR COALESCE(p.allows_snack, FALSE)) AS effective_snack
FROM employee e
LEFT JOIN `position` p ON p.id = e.position_id
WHERE e.deleted = FALSE;
