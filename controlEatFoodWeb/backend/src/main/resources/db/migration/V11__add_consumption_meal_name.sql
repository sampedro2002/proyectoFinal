-- V11: Completa el refactor de tipos de comida.
--
-- La entidad Consumption ahora usa un texto libre `meal_name` (Desayuno/Almuerzo/Manual/…)
-- en lugar de la relación con meal_type (eliminada en V9). Faltaba crear la columna, lo que
-- rompía el arranque con spring.jpa.hibernate.ddl-auto=validate.
--
-- Además se limpian los restos de meal_type que quedaron en otras tablas: la FK NOT NULL
-- schedule.meal_type_id era una bomba de tiempo (un INSERT de horario nuevo fallaría), y ya
-- ninguna entidad ni vista referencia la tabla meal_type.
--
-- Los nombres de FOREIGN KEY se resuelven dinámicamente (los autogenerados *_ibfk_N pueden
-- variar entre entornos), de modo que la migración es portable a una BD reconstruida desde V1.

-- 1) Columna que faltaba
ALTER TABLE consumption
    ADD COLUMN meal_name VARCHAR(30) NULL AFTER sync_status;

-- 2) Quitar resto de meal_type en failed_scan (columna opcional, ya no mapeada por la entidad)
SET @fk := (SELECT constraint_name FROM information_schema.key_column_usage
            WHERE table_schema = DATABASE() AND table_name = 'failed_scan'
              AND referenced_table_name = 'meal_type' LIMIT 1);
SET @sql := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE failed_scan DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
ALTER TABLE failed_scan DROP COLUMN meal_type_id;

-- 3) Quitar resto NOT NULL en schedule (ya no mapeado; el horario es único y de texto)
SET @fk := (SELECT constraint_name FROM information_schema.key_column_usage
            WHERE table_schema = DATABASE() AND table_name = 'schedule'
              AND referenced_table_name = 'meal_type' LIMIT 1);
SET @sql := IF(@fk IS NOT NULL, CONCAT('ALTER TABLE schedule DROP FOREIGN KEY ', @fk), 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
ALTER TABLE schedule DROP COLUMN meal_type_id;

-- 4) Eliminar la tabla meal_type: ya no tiene FKs entrantes ni uso en el código
DROP TABLE IF EXISTS meal_type;
