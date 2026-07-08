-- ============================================================================
-- V9: Elimina la columna huérfana `consumption.meal_type_id`.
--
--   El concepto de "tipo de comida" por registro se retiró (V4/V7/V8): la
--   entidad JPA `Consumption` ya NO mapea `meal_type_id`, pero la columna seguía
--   existiendo en la tabla como `NOT NULL` sin valor por defecto. Por eso todo
--   INSERT de un consumo (huella con coincidencia, registro manual o externo)
--   fallaba con "Field 'meal_type_id' doesn't have a default value" →
--   DataIntegrityViolationException → HTTP 500 en /api/scan.
--
--   Esta migración:
--     1. Elimina la vista que dependía de `meal_type_id`.
--     2. Elimina el índice único antiguo (employee_id, meal_type_id, business_date).
--     3. Elimina la FK y la columna `meal_type_id` de `consumption`.
--     4. Recrea la vista de consumo diario sin el tipo de comida.
--     5. Reemplaza el índice por uno NO único (employee_id, business_date) para
--        acelerar la comprobación de "ya consumió hoy". No se usa un índice ÚNICO
--        porque los datos históricos pueden tener 2 filas por día y empleado
--        (almuerzo + merienda con distinto meal_type_id), lo que haría fallar la
--        migración. La unicidad por día ya la garantiza la lógica de negocio
--        (ScanService.resolveMealForScan) y la idempotencia por client_uuid.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Vista dependiente (referencia c.meal_type_id).
-- ----------------------------------------------------------------------------
DROP VIEW IF EXISTS v_daily_consumption;

-- ----------------------------------------------------------------------------
-- 2. Índice único antiguo que incluye meal_type_id.
-- ----------------------------------------------------------------------------
DROP INDEX uq_consumption_per_day ON consumption;

-- ----------------------------------------------------------------------------
-- 3. Eliminar la FK (nombre autogenerado por InnoDB) y luego la columna.
-- ----------------------------------------------------------------------------
SET @fk := (SELECT CONSTRAINT_NAME
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'consumption'
              AND COLUMN_NAME = 'meal_type_id'
              AND REFERENCED_TABLE_NAME IS NOT NULL
            LIMIT 1);
SET @sql := IF(@fk IS NOT NULL,
               CONCAT('ALTER TABLE consumption DROP FOREIGN KEY `', @fk, '`'),
               'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ALTER TABLE consumption DROP COLUMN meal_type_id;

-- ----------------------------------------------------------------------------
-- 4. Recrear la vista de consumo diario sin tipo de comida.
-- ----------------------------------------------------------------------------
CREATE VIEW v_daily_consumption AS
SELECT c.business_date,
       c.restaurant_id,
       'GENERAL' AS meal_code,
       COUNT(*)  AS total_records
FROM consumption c
GROUP BY c.business_date, c.restaurant_id;

-- ----------------------------------------------------------------------------
-- 5. Índice de apoyo para la comprobación "ya consumió hoy" (NO único; ver nota).
-- ----------------------------------------------------------------------------
CREATE INDEX idx_consumption_employee_date ON consumption(employee_id, business_date);
