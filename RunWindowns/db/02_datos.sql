-- ============================================================================
-- Datos iniciales (seed) — estado final consolidado
--
-- Idempotente por diseño (mismo criterio que los CREATE TABLE IF NOT EXISTS
-- de V1): en operación normal Flyway ejecuta este script UNA sola vez por
-- base de datos (flyway_schema_history), pero si se corre contra una base
-- que ya contiene estos registros (p. ej. un esquema existente re-baselineado
-- tras perder la tabla de historia), los INSERT IGNORE / WHERE NOT EXISTS
-- evitan errores de clave duplicada y respetan los datos existentes.
-- ============================================================================

-- Roles del sistema (nombre es UNIQUE: IGNORE omite los que ya existan)
INSERT IGNORE INTO roles (nombre, descripcion) VALUES
  ('ADMIN',             'Administrador del sistema'),
  ('CATERING',          'Registro de consumos en punto de restaurante'),
  ('RECURSOS_HUMANOS',  'Recursos Humanos — reportes y empleados (solo lectura)');

-- Usuario administrador inicial (nombre_usuario es UNIQUE). La contraseña real
-- (Admin123*) la cifra DataInitializer al arrancar (BCrypt), reemplazando
-- este placeholder. Si 'admin' ya existe, se conserva tal cual está.
INSERT IGNORE INTO usuario (nombre_usuario, contrasena_hash, nombre_completo, correo, habilitado)
VALUES ('admin', 'NEEDS_RESET', 'Administrador', 'admin@eatfood.local', TRUE);

-- Asignación de rol ADMIN (PK compuesta usuario_id+rol_id: IGNORE evita duplicado)
INSERT IGNORE INTO usuario_rol (usuario_id, rol_id)
SELECT u.id, r.id FROM usuario u, roles r WHERE u.nombre_usuario='admin' AND r.nombre='ADMIN';

-- Usuario de Recursos Humanos (contraseña la fija DataInitializer)
INSERT IGNORE INTO usuario (nombre_usuario, contrasena_hash, nombre_completo, correo, habilitado)
VALUES ('rrhh', 'NEEDS_RESET', 'Recursos Humanos', 'rrhh@eatfood.local', TRUE);

INSERT IGNORE INTO usuario_rol (usuario_id, rol_id)
SELECT u.id, r.id FROM usuario u, roles r WHERE u.nombre_usuario='rrhh' AND r.nombre='RECURSOS_HUMANOS';

-- 3 restaurantes iniciales (nombre es UNIQUE)
INSERT IGNORE INTO restaurante (nombre, ubicacion, max_dispositivos) VALUES
  ('Restaurante Norte',  'Edificio A - Planta 1', 2),
  ('Restaurante Centro', 'Edificio B - Comedor central', 2),
  ('Restaurante Sur',    'Edificio C - Planta baja', 2);

-- Horario único de comidas. La tabla horario no tiene clave única natural,
-- así que se inserta solo si la tabla está vacía (evita un segundo horario).
INSERT INTO horario (hora_inicio, hora_fin)
SELECT '11:00:00', '16:00:00' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM horario);
