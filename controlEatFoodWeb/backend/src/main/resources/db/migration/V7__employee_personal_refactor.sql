-- ============================================================================
-- V7: El cargo deja de ser una entidad (tabla `position`) y pasa a ser texto
-- libre en el empleado. Se agrega un código público seguro (EMP-XXXXXX) y un
-- campo de observación. Se elimina el rol SUPERVISOR (queda ADMIN y CATERING).
-- 
-- Compatible con MySQL 8+ y MariaDB 10.5+
-- ============================================================================

DROP PROCEDURE IF EXISTS migrate_v7;

DELIMITER //

CREATE PROCEDURE migrate_v7()
BEGIN
    DECLARE col_exists INT DEFAULT 0;
    DECLARE idx_exists INT DEFAULT 0;
    DECLARE tbl_exists INT DEFAULT 0;
    DECLARE fk_name VARCHAR(64);
    
    -- ----------------------------------------------------------------------------
    -- 1. Nuevas columnas del empleado
    -- ----------------------------------------------------------------------------
    
    -- Agregar public_code si no existe
    SELECT COUNT(*) INTO col_exists FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employee' AND COLUMN_NAME = 'public_code';
    IF col_exists = 0 THEN
        ALTER TABLE employee ADD COLUMN public_code VARCHAR(12);
    END IF;
    
    -- Agregar observation si no existe
    SELECT COUNT(*) INTO col_exists FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employee' AND COLUMN_NAME = 'observation';
    IF col_exists = 0 THEN
        ALTER TABLE employee ADD COLUMN observation VARCHAR(500);
    END IF;
    
    -- Agregar position_title si no existe
    SELECT COUNT(*) INTO col_exists FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employee' AND COLUMN_NAME = 'position_title';
    IF col_exists = 0 THEN
        ALTER TABLE employee ADD COLUMN position_title VARCHAR(120);
    END IF;
    
    -- Copiar el nombre del cargo si la tabla position aún existe
    SELECT COUNT(*) INTO tbl_exists FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'position';
    IF tbl_exists > 0 THEN
        UPDATE employee e JOIN `position` p ON p.id = e.position_id 
        SET e.position_title = p.name 
        WHERE e.position_title IS NULL;
    END IF;
    
    -- Backfill del código público para filas existentes
    UPDATE employee SET public_code = CONCAT('EMP-', LPAD(id, 6, '0')) WHERE public_code IS NULL;
    
    -- Volver public_code obligatorio
    ALTER TABLE employee MODIFY COLUMN public_code VARCHAR(12) NOT NULL;
    
    -- Crear índice único si no existe
    SELECT COUNT(*) INTO idx_exists FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employee' AND INDEX_NAME = 'uq_employee_public_code';
    IF idx_exists = 0 THEN
        CREATE UNIQUE INDEX uq_employee_public_code ON employee(public_code);
    END IF;
    
    -- ----------------------------------------------------------------------------
    -- 2. Reescribir la vista de configuración efectiva del empleado sin `position`
    -- ----------------------------------------------------------------------------
    DROP VIEW IF EXISTS v_employee_effective_config;
    CREATE VIEW v_employee_effective_config AS
    SELECT e.id AS employee_id,
           e.full_name,
           e.position_title AS position_name,
           e.allows_lunch,
           e.allows_snack AS effective_snack
    FROM employee e
    WHERE e.deleted = FALSE;
    
    -- ----------------------------------------------------------------------------
    -- 3. Eliminar la FK, el índice y la columna position_id; luego la tabla
    -- ----------------------------------------------------------------------------
    
    -- Buscar y eliminar FK
    SELECT CONSTRAINT_NAME INTO fk_name FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'employee'
      AND COLUMN_NAME = 'position_id'
      AND REFERENCED_TABLE_NAME = 'position'
    LIMIT 1;
    
    IF fk_name IS NOT NULL THEN
        SET @sql = CONCAT('ALTER TABLE employee DROP FOREIGN KEY ', fk_name);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
    
    -- Eliminar índice si existe
    SELECT COUNT(*) INTO idx_exists FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employee' AND INDEX_NAME = 'idx_employee_position';
    IF idx_exists > 0 THEN
        DROP INDEX idx_employee_position ON employee;
    END IF;
    
    -- Eliminar columna si existe
    SELECT COUNT(*) INTO col_exists FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'employee' AND COLUMN_NAME = 'position_id';
    IF col_exists > 0 THEN
        ALTER TABLE employee DROP COLUMN position_id;
    END IF;
    
    -- Eliminar tabla position
    DROP TABLE IF EXISTS `position`;
    
    -- ----------------------------------------------------------------------------
    -- 4. Eliminar el rol SUPERVISOR y sus asignaciones
    -- ----------------------------------------------------------------------------
    DELETE FROM user_roles WHERE role_id IN (SELECT id FROM (SELECT id FROM roles WHERE name = 'SUPERVISOR') AS r);
    DELETE FROM roles WHERE name = 'SUPERVISOR';
    
END //

DELIMITER ;

-- Ejecutar la migración
CALL migrate_v7();

-- Limpiar el procedimiento temporal
DROP PROCEDURE IF EXISTS migrate_v7;
