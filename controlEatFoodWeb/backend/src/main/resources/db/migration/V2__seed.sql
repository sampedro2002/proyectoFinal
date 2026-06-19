-- ============================================================================
-- Datos iniciales (seed)
-- ============================================================================

INSERT INTO roles (name, description) VALUES
  ('ADMIN',      'Administrador del sistema'),
  ('SUPERVISOR', 'Consulta de reportes y estadísticas'),
  ('CATERING',   'Registro de consumos en punto de catering');

-- Usuario administrador inicial.  La contraseña real (Admin123*) la cifra
-- DataInitializer al arrancar (BCrypt), reemplazando este placeholder.
INSERT INTO app_user (username, password_hash, full_name, email, enabled)
VALUES ('admin', 'NEEDS_RESET', 'Administrador', 'admin@eatfood.local', TRUE);

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM app_user u, roles r WHERE u.username='admin' AND r.name='ADMIN';

-- Tipos de comida iniciales
INSERT INTO meal_type (code, name, sort_order) VALUES
  ('LUNCH', 'Almuerzo', 1),
  ('SNACK', 'Merienda', 2);

-- Horarios iniciales
INSERT INTO schedule (meal_type_id, start_time, end_time)
SELECT id, '12:00:00', '13:30:00' FROM meal_type WHERE code='LUNCH';
INSERT INTO schedule (meal_type_id, start_time, end_time)
SELECT id, '18:00:00', '19:00:00' FROM meal_type WHERE code='SNACK';

-- Cargos de ejemplo
INSERT INTO `position` (name, default_plates, allows_snack) VALUES
  ('Operario', 1, FALSE),
  ('Supervisor de Planta', 1, TRUE),
  ('Administrativo', 1, FALSE);

-- 3 caterings iniciales
INSERT INTO catering (name, location, max_devices) VALUES
  ('Catering Norte',  'Edificio A - Planta 1', 2),
  ('Catering Centro', 'Edificio B - Comedor central', 2),
  ('Catering Sur',    'Edificio C - Planta baja', 2);

-- Configuración global
INSERT INTO app_config (config_key, config_value, description) VALUES
  ('biometric.match.threshold', '70', 'Umbral mínimo de matching 1:N del SDK'),
  ('catering.max.devices', '2', 'Máximo de dispositivos simultáneos por catering'),
  ('ui.success.display.seconds', '10', 'Segundos que se muestra el registro exitoso');
