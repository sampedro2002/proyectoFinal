-- Ejecutable desde MySQL Workbench/mysql y desde Flyway.
-- Seleccionar la base destino antes de ejecutar. No ejecutar simultaneamente
-- con otro despliegue. No modificar este archivo despues de su primera migracion.
-- Requiere ALTER, SELECT, CREATE ROUTINE, ALTER ROUTINE y EXECUTE.
-- El procedimiento es auxiliar de esta migracion y se elimina al finalizar.
-- Un reintento limpia solamente este procedimiento si un intento previo fallo.

DROP PROCEDURE IF EXISTS cef_v3_tipo_personal;

DELIMITER $$;
CREATE PROCEDURE cef_v3_tipo_personal()
SQL SECURITY INVOKER
BEGIN
    DECLARE column_count INT DEFAULT 0;
    DECLARE valid_count INT DEFAULT 0;
    DECLARE invalid_count BIGINT DEFAULT 0;
    DECLARE previous_lock_wait BIGINT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        SET SESSION lock_wait_timeout = previous_lock_wait;
        RESIGNAL;
    END;

    SET previous_lock_wait = @@SESSION.lock_wait_timeout;
    -- Limita la espera por metadatos; NO limita la duracion total del ALTER.
    SET SESSION lock_wait_timeout = 15;

    SELECT COUNT(*) INTO column_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'empleado'
      AND column_name = 'tipo_personal';

    IF column_count = 0 THEN
        -- MySQL elige el algoritmo admitido. Probar antes en una copia con la
        -- misma version: segun version/estructura puede reconstruir la tabla.
        ALTER TABLE empleado
            ADD COLUMN tipo_personal VARCHAR(30) NOT NULL DEFAULT 'NOMINA';
    END IF;

    -- No aceptar silenciosamente una columna preexistente incompatible.
    SELECT COUNT(*) INTO valid_count
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'empleado'
      AND column_name = 'tipo_personal'
      AND data_type = 'varchar' AND character_maximum_length = 30
      AND is_nullable = 'NO' AND BINARY column_default = BINARY 'NOMINA'
      AND extra = '';

    IF valid_count <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'tipo_personal incompatible: se requiere VARCHAR(30) NOT NULL DEFAULT NOMINA';
    END IF;

    SELECT COUNT(*) INTO invalid_count FROM empleado
    WHERE tipo_personal IS NULL
       OR BINARY tipo_personal NOT IN (BINARY 'NOMINA', BINARY 'SERVICIOS_PROFESIONALES');
    IF invalid_count <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'tipo_personal contiene valores invalidos; revisar antes de desplegar';
    END IF;

    SET SESSION lock_wait_timeout = previous_lock_wait;
END$$
DELIMITER ;

CALL cef_v3_tipo_personal();
DROP PROCEDURE cef_v3_tipo_personal;
