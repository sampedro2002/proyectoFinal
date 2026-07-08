-- ============================================================================
-- V8: Refactor "Restaurante"
--   1. Renombra la entidad `catering` -> `restaurant` en todo el esquema.
--   2. Añade `representative` (responsable del restaurante).
--   3. Añade `observation` al registro de consumo (para el registro manual).
--   4. Elimina el cargo del empleado (`position_title`); el permiso de comidas
--      queda ligado a la cédula (allows_lunch / allows_snack).
--   5. Fija un horario único 11:00-16:00 para almuerzo y merienda (se registran
--      ambas comidas en la misma ventana, por orden de huella).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Eliminar vistas dependientes (referencian position_title y catering_id).
--    Se recrean al final con los nombres nuevos y sin el cargo.
-- ----------------------------------------------------------------------------
DROP VIEW IF EXISTS v_daily_consumption;
DROP VIEW IF EXISTS v_employee_effective_config;

-- ----------------------------------------------------------------------------
-- 2. Renombrar la tabla catering -> restaurant.
--    InnoDB actualiza automáticamente las FKs de las tablas hijas.
-- ----------------------------------------------------------------------------
RENAME TABLE catering TO restaurant;

-- ----------------------------------------------------------------------------
-- 3. Renombrar la columna catering_id -> restaurant_id en las tablas hijas.
-- ----------------------------------------------------------------------------
ALTER TABLE app_user    RENAME COLUMN catering_id TO restaurant_id;
ALTER TABLE device      RENAME COLUMN catering_id TO restaurant_id;
ALTER TABLE consumption RENAME COLUMN catering_id TO restaurant_id;
ALTER TABLE failed_scan RENAME COLUMN catering_id TO restaurant_id;

-- ----------------------------------------------------------------------------
-- 4. Nuevas columnas.
-- ----------------------------------------------------------------------------
ALTER TABLE restaurant  ADD COLUMN representative VARCHAR(120);
ALTER TABLE consumption ADD COLUMN observation    VARCHAR(500);

-- ----------------------------------------------------------------------------
-- 5. Eliminar el cargo del empleado.
-- ----------------------------------------------------------------------------
ALTER TABLE employee DROP COLUMN position_title;

-- ----------------------------------------------------------------------------
-- 6. Horario único 11:00-16:00 para almuerzo y merienda.
-- ----------------------------------------------------------------------------
UPDATE schedule s
JOIN meal_type m ON m.id = s.meal_type_id
SET s.start_time = '11:00:00', s.end_time = '16:00:00', s.active = TRUE
WHERE m.code IN ('LUNCH', 'SNACK');

-- ----------------------------------------------------------------------------
-- 7. Recrear las vistas sin el cargo y con restaurant_id.
-- ----------------------------------------------------------------------------
CREATE VIEW v_daily_consumption AS
SELECT c.business_date,
       c.restaurant_id,
       mt.code  AS meal_code,
       COUNT(*) AS total_records
FROM consumption c
JOIN meal_type mt ON mt.id = c.meal_type_id
GROUP BY c.business_date, c.restaurant_id, mt.code;

CREATE VIEW v_employee_effective_config AS
SELECT e.id           AS employee_id,
       e.full_name,
       e.allows_lunch,
       e.allows_snack AS effective_snack
FROM employee e
WHERE e.deleted = FALSE;
