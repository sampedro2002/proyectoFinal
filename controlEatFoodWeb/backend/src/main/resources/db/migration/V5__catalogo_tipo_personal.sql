CREATE TABLE IF NOT EXISTS tipo_personal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    codigo VARCHAR(30) NOT NULL UNIQUE,
    nombre VARCHAR(80) NOT NULL,
    orden INT NOT NULL DEFAULT 0,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO tipo_personal (codigo, nombre, orden, activo)
SELECT 'NOMINA', 'Empleados de nómina', 1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM tipo_personal WHERE codigo = 'NOMINA');
INSERT INTO tipo_personal (codigo, nombre, orden, activo)
SELECT 'SERVICIOS_PROFESIONALES', 'Servicios profesionales', 2, TRUE
WHERE NOT EXISTS (SELECT 1 FROM tipo_personal WHERE codigo = 'SERVICIOS_PROFESIONALES');

DROP PROCEDURE IF EXISTS cef_v5_tipo_personal_fk;
DELIMITER $$
CREATE PROCEDURE cef_v5_tipo_personal_fk()
SQL SECURITY INVOKER
BEGIN
    DECLARE fk_count INT DEFAULT 0;
    DECLARE bad_count BIGINT DEFAULT 0;
    DECLARE employee_charset VARCHAR(64);
    DECLARE employee_collation VARCHAR(64);
    SELECT character_set_name, collation_name INTO employee_charset, employee_collation
      FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'empleado'
        AND column_name = 'tipo_personal';
    -- empleado.tipo_personal hereda el charset/collation de la tabla histórica;
    -- igualamos el catálogo antes de crear la FK para que MySQL pueda indexarlo.
    SET @cef_v5_codigo_ddl = CONCAT(
      'ALTER TABLE tipo_personal MODIFY codigo VARCHAR(30) CHARACTER SET ',
      employee_charset, ' COLLATE ', employee_collation, ' NOT NULL');
    PREPARE cef_v5_codigo_stmt FROM @cef_v5_codigo_ddl;
    EXECUTE cef_v5_codigo_stmt;
    DEALLOCATE PREPARE cef_v5_codigo_stmt;
    SELECT COUNT(*) INTO bad_count FROM empleado e
      LEFT JOIN tipo_personal t ON BINARY e.tipo_personal = BINARY t.codigo
      WHERE t.codigo IS NULL;
    IF bad_count <> 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Hay empleados con tipo_personal sin catálogo';
    END IF;
    SELECT COUNT(*) INTO fk_count FROM information_schema.key_column_usage
      WHERE table_schema = DATABASE() AND table_name = 'empleado'
        AND column_name = 'tipo_personal' AND referenced_table_name = 'tipo_personal'
        AND referenced_column_name = 'codigo';
    IF fk_count = 0 THEN
      ALTER TABLE empleado ADD CONSTRAINT fk_empleado_tipo_personal
        FOREIGN KEY (tipo_personal) REFERENCES tipo_personal(codigo);
    END IF;
END$$
DELIMITER ;
CALL cef_v5_tipo_personal_fk();
DROP PROCEDURE cef_v5_tipo_personal_fk;
