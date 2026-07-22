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
    nombre      VARCHAR(40) NOT NULL UNIQUE,         -- ADMIN, CATERING
    descripcion VARCHAR(120)
);

-- ----------------------------------------------------------------------------
-- RESTAURANTES
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS restaurante (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    nombre         VARCHAR(120) NOT NULL UNIQUE,
    ubicacion      VARCHAR(160),
    representante  VARCHAR(120),
    activo         BOOLEAN NOT NULL DEFAULT TRUE,
    max_dispositivos    INT NOT NULL DEFAULT 2 CHECK (max_dispositivos > 0),
    creado_en     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS usuario (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    nombre_usuario        VARCHAR(60) NOT NULL UNIQUE,
    contrasena_hash   VARCHAR(120) NOT NULL,
    nombre_completo       VARCHAR(120) NOT NULL,
    correo           VARCHAR(120),
    habilitado         BOOLEAN NOT NULL DEFAULT TRUE,
    intentos_fallidos INT NOT NULL DEFAULT 0,
    bloqueado_hasta    DATETIME,
    restaurante_id  BIGINT,
    creado_en      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_usuario_restaurante FOREIGN KEY (restaurante_id) REFERENCES restaurante(id)
);

CREATE TABLE IF NOT EXISTS usuario_rol (
    usuario_id BIGINT NOT NULL,
    rol_id BIGINT NOT NULL,
    PRIMARY KEY (usuario_id, rol_id),
    FOREIGN KEY (usuario_id) REFERENCES usuario(id) ON DELETE CASCADE,
    FOREIGN KEY (rol_id) REFERENCES roles(id)
);

-- ----------------------------------------------------------------------------
-- EMPLEADOS (el identificador es el id autoincremental; no hay código público)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS empleado (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    cedula   VARCHAR(20) NOT NULL UNIQUE,
    nombre_completo   VARCHAR(160) NOT NULL,
    estado          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (estado IN ('ACTIVE','INACTIVE')),
    observacion     VARCHAR(500),
    permite_almuerzo    BOOLEAN NOT NULL DEFAULT TRUE,
    permite_merienda    BOOLEAN NOT NULL DEFAULT FALSE,
    eliminado         BOOLEAN NOT NULL DEFAULT FALSE,
    creado_en      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_empleado_estado_no_eliminado (estado, eliminado)
);

-- ----------------------------------------------------------------------------
-- HUELLAS
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS huella_digital (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    empleado_id   BIGINT NOT NULL,
    indice_dedo  SMALLINT NOT NULL CHECK (indice_dedo BETWEEN 0 AND 9),
    plantilla      BLOB NOT NULL,
    registrado_por   BIGINT,
    registrado_en   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activo        BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE KEY uq_huella_empleado_dedo (empleado_id, indice_dedo),
    KEY idx_huella_empleado (empleado_id),
    FOREIGN KEY (empleado_id) REFERENCES empleado(id) ON DELETE CASCADE,
    FOREIGN KEY (registrado_por) REFERENCES usuario(id)
);

-- Nota: el límite de 3 huellas activas por empleado se impone en FingerprintService
-- (MAX_FINGERPRINTS = 3), no en la base de datos.

-- ----------------------------------------------------------------------------
-- DISPOSITIVOS
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dispositivo (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurante_id BIGINT NOT NULL,
    uid_dispositivo    VARCHAR(80) NOT NULL,
    name          VARCHAR(120),
    ultima_conexion     DATETIME,
    token_sesion VARCHAR(120),
    conectado     BOOLEAN NOT NULL DEFAULT FALSE,
    creado_en    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_dispositivo_restaurante_uid (restaurante_id, uid_dispositivo),
    KEY idx_dispositivo_restaurante_conectado (restaurante_id, conectado),
    FOREIGN KEY (restaurante_id) REFERENCES restaurante(id)
);

-- ----------------------------------------------------------------------------
-- HORARIO (único, aplica a todas las comidas del día)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS horario (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    hora_inicio TIME NOT NULL,
    hora_fin   TIME NOT NULL,
    activo     BOOLEAN NOT NULL DEFAULT TRUE,
    actualizado_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CHECK (hora_fin > hora_inicio)
);

-- ----------------------------------------------------------------------------
-- REGISTRO DE CONSUMO
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS consumo (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    empleado_id     BIGINT NOT NULL,
    restaurante_id   BIGINT NOT NULL,
    dispositivo_id       BIGINT,
    consumido_en     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_negocio   DATE NOT NULL,
    sin_conexion         BOOLEAN NOT NULL DEFAULT FALSE,
    estado_sincronizacion     VARCHAR(12) NOT NULL DEFAULT 'SYNCED' CHECK (estado_sincronizacion IN ('SYNCED','PENDING','CONFLICT')),
    -- method: origen del registro. FINGERPRINT = escaneo de huella del propio
    -- empleado; MANUAL = registro manual "retira por otro" (proxy_employee_id
    -- indica quién retira); EXTERNAL = persona externa creada al vuelo.
    metodo          VARCHAR(12) NOT NULL DEFAULT 'FINGERPRINT'
                        CHECK (metodo IN ('FINGERPRINT','MANUAL','EXTERNAL')),
    empleado_apoderado_id BIGINT,               -- empleado que retira (solo method='MANUAL')
    nombre_comida       VARCHAR(30),          -- 'Almuerzo' (1er plato) o 'Merienda' (2º plato)
    observacion     VARCHAR(500),
    uuid_cliente        VARCHAR(36) NOT NULL,
    cancelado           BOOLEAN NOT NULL DEFAULT FALSE,
    creado_en           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_consumo_uuid_cliente (uuid_cliente),
    KEY idx_consumo_fecha (fecha_negocio),
    KEY idx_consumo_restaurante_fecha (restaurante_id, fecha_negocio),
    KEY idx_consumo_empleado_fecha (empleado_id, fecha_negocio),
    KEY idx_consumo_metodo (metodo),
    KEY idx_consumo_apoderado (empleado_apoderado_id),
    FOREIGN KEY (empleado_id) REFERENCES empleado(id),
    FOREIGN KEY (restaurante_id) REFERENCES restaurante(id),
    FOREIGN KEY (dispositivo_id) REFERENCES dispositivo(id),
    FOREIGN KEY (empleado_apoderado_id) REFERENCES empleado(id) ON DELETE SET NULL
);

-- Nota: no hay índice único por (empleado, día): el tope de comidas por día
-- lo controla ScanService, y la idempotencia por reintentos la da client_uuid.

-- ----------------------------------------------------------------------------
-- INTENTOS FALLIDOS
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS escaneo_fallido (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurante_id BIGINT,
    dispositivo_id     BIGINT,
    empleado_id   BIGINT,
    razon        VARCHAR(30) NOT NULL,
    ocurrido_en   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_escaneo_fallido_fecha (ocurrido_en),
    FOREIGN KEY (restaurante_id) REFERENCES restaurante(id),
    FOREIGN KEY (dispositivo_id) REFERENCES dispositivo(id),
    FOREIGN KEY (empleado_id) REFERENCES empleado(id)
);

-- ----------------------------------------------------------------------------
-- AUDITORÍA
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS registro_auditoria (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    nombre_usuario     VARCHAR(60),
    nombre_entidad  VARCHAR(60) NOT NULL,
    id_entidad    VARCHAR(40),
    accion       VARCHAR(20) NOT NULL,
    valor_anterior    TEXT,
    valor_nuevo    TEXT,
    direccion_ip   VARCHAR(60),
    info_dispositivo  VARCHAR(200),
    creado_en   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_auditoria_entidad (nombre_entidad, id_entidad),
    KEY idx_auditoria_fecha (creado_en)
);

-- ----------------------------------------------------------------------------
-- SESIONES
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sesion_inicio (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    usuario_id       BIGINT NOT NULL,
    token_refresco VARCHAR(200) NOT NULL,
    direccion_ip    VARCHAR(60),
    info_dispositivo   VARCHAR(200),
    emitido_en     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expira_en    DATETIME NOT NULL,
    revocado       BOOLEAN NOT NULL DEFAULT FALSE,
    KEY idx_sesion_usuario (usuario_id),
    FOREIGN KEY (usuario_id) REFERENCES usuario(id)
);

-- ----------------------------------------------------------------------------
-- VISTAS (CREATE OR REPLACE: siempre queda con la definición más reciente)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_consumo_diario AS
SELECT c.fecha_negocio,
       c.restaurante_id,
       'GENERAL' AS meal_code,
       COUNT(*)  AS total_records
FROM consumo c
GROUP BY c.fecha_negocio, c.restaurante_id;

CREATE OR REPLACE VIEW v_config_efectiva_empleado AS
SELECT e.id AS empleado_id,
       e.nombre_completo,
       e.permite_almuerzo,
       e.permite_merienda AS merienda_efectiva
FROM empleado e
WHERE e.eliminado = FALSE;
