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

-- Roles del sistema (name es UNIQUE: IGNORE omite los que ya existan)
INSERT IGNORE INTO roles (name, description) VALUES
  ('ADMIN',    'Administrador del sistema'),
  ('CATERING', 'Registro de consumos en punto de restaurante');

-- Usuario administrador inicial (username es UNIQUE). La contraseña real
-- (Admin123*) la cifra DataInitializer al arrancar (BCrypt), reemplazando
-- este placeholder. Si 'admin' ya existe, se conserva tal cual está.
INSERT IGNORE INTO app_user (username, password_hash, full_name, email, enabled)
VALUES ('admin', 'NEEDS_RESET', 'Administrador', 'admin@eatfood.local', TRUE);

-- Asignación de rol ADMIN (PK compuesta user_id+role_id: IGNORE evita duplicado)
INSERT IGNORE INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM app_user u, roles r WHERE u.username='admin' AND r.name='ADMIN';

-- 3 restaurantes iniciales (name es UNIQUE)
INSERT IGNORE INTO restaurant (name, location, max_devices) VALUES
  ('Restaurante Norte',  'Edificio A - Planta 1', 2),
  ('Restaurante Centro', 'Edificio B - Comedor central', 2),
  ('Restaurante Sur',    'Edificio C - Planta baja', 2);

-- Horario único de comidas. La tabla schedule no tiene clave única natural,
-- así que se inserta solo si la tabla está vacía (evita un segundo horario).
INSERT INTO schedule (start_time, end_time)
SELECT '11:00:00', '16:00:00' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM schedule);
