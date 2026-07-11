-- ============================================================================
-- Datos iniciales (seed) — estado final consolidado
-- ============================================================================

INSERT INTO roles (name, description) VALUES
  ('ADMIN',    'Administrador del sistema'),
  ('CATERING', 'Registro de consumos en punto de restaurante');

-- Usuario administrador inicial. La contraseña real (Admin123*) la cifra
-- DataInitializer al arrancar (BCrypt), reemplazando este placeholder.
INSERT INTO app_user (username, password_hash, full_name, email, enabled)
VALUES ('admin', 'NEEDS_RESET', 'Administrador', 'admin@eatfood.local', TRUE);

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM app_user u, roles r WHERE u.username='admin' AND r.name='ADMIN';

-- 3 restaurantes iniciales
INSERT INTO restaurant (name, location, max_devices) VALUES
  ('Restaurante Norte',  'Edificio A - Planta 1', 2),
  ('Restaurante Centro', 'Edificio B - Comedor central', 2),
  ('Restaurante Sur',    'Edificio C - Planta baja', 2);

-- Horario único de comidas
INSERT INTO schedule (start_time, end_time) VALUES ('11:00:00', '16:00:00');

-- Configuración global
INSERT INTO app_config (config_key, config_value, description) VALUES
  ('biometric.match.threshold', '70', 'Umbral mínimo de matching 1:N del SDK'),
  ('catering.max.devices', '2', 'Máximo de dispositivos simultáneos por restaurante'),
  ('ui.success.display.seconds', '10', 'Segundos que se muestra el registro exitoso');
