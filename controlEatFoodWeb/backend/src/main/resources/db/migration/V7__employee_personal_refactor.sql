-- ============================================================================
-- V7: El cargo deja de ser una entidad (tabla `position`) y pasa a ser texto
-- libre en el empleado. Se agrega un código público seguro (EMP-XXXXXX) y un
-- campo de observación. Se elimina el rol SUPERVISOR (queda ADMIN y CATERING).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Nuevas columnas del empleado
-- ----------------------------------------------------------------------------
ALTER TABLE employee ADD COLUMN public_code    VARCHAR(12);
ALTER TABLE employee ADD COLUMN observation    VARCHAR(500);
ALTER TABLE employee ADD COLUMN position_title VARCHAR(120);

-- Copiar el nombre del cargo (position.name) al nuevo campo de texto.
UPDATE employee e
JOIN `position` p ON p.id = e.position_id
SET e.position_title = p.name;

-- Backfill del código público para filas existentes: EMP- + id con cero a la
-- izquierda (garantiza unicidad porque el id es único).
UPDATE employee SET public_code = CONCAT('EMP-', LPAD(id, 6, '0')) WHERE public_code IS NULL;

-- Volver public_code obligatorio y único.
ALTER TABLE employee MODIFY COLUMN public_code VARCHAR(12) NOT NULL;
CREATE UNIQUE INDEX uq_employee_public_code ON employee(public_code);

-- ----------------------------------------------------------------------------
-- 2. Reescribir la vista de configuración efectiva del empleado sin `position`
--    (debe recrearse ANTES de eliminar la tabla/columna que referenciaba).
-- ----------------------------------------------------------------------------
DROP VIEW IF EXISTS v_employee_effective_config;
CREATE VIEW v_employee_effective_config AS
SELECT e.id            AS employee_id,
       e.full_name,
       e.position_title AS position_name,
       e.allows_lunch,
       e.allows_snack   AS effective_snack
FROM employee e
WHERE e.deleted = FALSE;

-- ----------------------------------------------------------------------------
-- 3. Eliminar la FK, el índice y la columna position_id; luego la tabla.
--    El nombre de la FK lo genera MySQL automáticamente, por lo que se busca
--    dinámicamente en information_schema antes de eliminarla.
-- ----------------------------------------------------------------------------
SET @fk := (SELECT CONSTRAINT_NAME
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'employee'
              AND COLUMN_NAME = 'position_id'
              AND REFERENCED_TABLE_NAME = 'position'
            LIMIT 1);
SET @sql := IF(@fk IS NOT NULL,
               CONCAT('ALTER TABLE employee DROP FOREIGN KEY ', @fk),
               'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

DROP INDEX idx_employee_position ON employee;
ALTER TABLE employee DROP COLUMN position_id;
DROP TABLE `position`;

-- ----------------------------------------------------------------------------
-- 4. Eliminar el rol SUPERVISOR y sus asignaciones.
-- ----------------------------------------------------------------------------
DELETE FROM user_roles WHERE role_id IN (SELECT id FROM (SELECT id FROM roles WHERE name = 'SUPERVISOR') AS r);
DELETE FROM roles WHERE name = 'SUPERVISOR';
