-- ============================================================================
-- Control de Alimentos Club Castillo Amaguaña — Datos iniciales
--
-- Lo ejecuta Inicio.ps1 después de 01_esquema.sql cuando la base es nueva.
-- Idempotente: los INSERT IGNORE / WHERE NOT EXISTS permiten re-ejecutarlo
-- contra una base que ya tenga estos registros sin errores de duplicado.
-- ============================================================================

-- Roles del sistema (name es UNIQUE: IGNORE omite los que ya existan)
INSERT IGNORE INTO roles (name, description) VALUES
  ('ADMIN',    'Administrador del sistema'),
  ('CATERING', 'Registro de consumos en punto de restaurante');

-- Usuario administrador inicial (username es UNIQUE). La contraseña real
-- (Admin123*) la cifra DataInitializer al arrancar el backend (BCrypt),
-- reemplazando este placeholder. Si 'admin' ya existe, se conserva tal cual.
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
