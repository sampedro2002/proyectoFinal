-- ============================================================================
-- PERSONAS EXTERNAS (visitantes, contratistas, invitados)
--
-- Delta sobre V1: las personas externas viven en una tabla SEPARADA de
-- empleado — nunca aparecen en la gestión ni en la exportación de empleados.
-- Un consumo pasa a tener DOS titulares posibles, excluyentes entre sí:
-- empleado interno (consumo.empleado_id) o persona externa
-- (consumo.persona_externa_id). Una persona externa registrada también puede
-- ser quien retira el plato (consumo.persona_externa_apoderada_id).
--
-- Esto vivía dentro de V1 mientras el esquema se consolidaba, pero V1 ya está
-- aplicada en instalaciones reales: editarla rompe la validación de checksum de
-- Flyway y, peor, deja la base de datos sin estos cambios. V1 es INMUTABLE a
-- partir de aquí; todo cambio de esquema va en una migración nueva (V4, V5...).
--
-- IDEMPOTENTE: hay instalaciones cuya V1 ya traía persona_externa (versión de
-- V1 del 2026-07-29, checksum -449241297). Ahí cada paso detecta que la
-- estructura existe y no hace nada; en las demás la crea. El procedimiento es
-- auxiliar de esta migración y se elimina al finalizar.
-- ============================================================================

CREATE TABLE IF NOT EXISTS persona_externa (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    cedula            VARCHAR(20) NOT NULL UNIQUE,
    nombre_completo   VARCHAR(160) NOT NULL,
    observacion       VARCHAR(500),
    creado_en         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

DROP PROCEDURE IF EXISTS cef_v3_persona_externa;

DELIMITER $$
CREATE PROCEDURE cef_v3_persona_externa()
SQL SECURITY INVOKER
BEGIN
    -- empleado_id deja de ser obligatorio: es NULL en los consumos cuyo
    -- titular es una persona externa (metodo='EXTERNAL').
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'consumo'
                 AND column_name = 'empleado_id' AND is_nullable = 'NO') THEN
        ALTER TABLE consumo MODIFY COLUMN empleado_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'consumo'
                     AND column_name = 'persona_externa_id') THEN
        ALTER TABLE consumo ADD COLUMN persona_externa_id BIGINT NULL AFTER empleado_id;
    END IF;

    -- Quien retira (apoderado), opcional: empleado (empleado_apoderado_id) O
    -- persona externa registrada (persona_externa_apoderada_id). Nunca ambos:
    -- lo valida ScanService/ManualConsumptionService, no hay CHECK en BD porque
    -- MySQL prohíbe un CHECK sobre una columna usada en una FK con ON DELETE
    -- SET NULL (error 3823), y empleado_apoderado_id es justamente así.
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'consumo'
                     AND column_name = 'persona_externa_apoderada_id') THEN
        ALTER TABLE consumo
            ADD COLUMN persona_externa_apoderada_id BIGINT NULL AFTER empleado_apoderado_id;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'consumo'
                     AND index_name = 'idx_consumo_externo_fecha') THEN
        ALTER TABLE consumo ADD KEY idx_consumo_externo_fecha (persona_externa_id, fecha_negocio);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'consumo'
                     AND index_name = 'idx_consumo_apoderado_externo') THEN
        ALTER TABLE consumo ADD KEY idx_consumo_apoderado_externo (persona_externa_apoderada_id);
    END IF;

    -- Las FK se buscan por columna, no por nombre: la V1 antigua las creó sin
    -- nombre explícito (consumo_ibfk_N).
    IF NOT EXISTS (SELECT 1 FROM information_schema.key_column_usage
                   WHERE table_schema = DATABASE() AND table_name = 'consumo'
                     AND column_name = 'persona_externa_id'
                     AND referenced_table_name = 'persona_externa') THEN
        ALTER TABLE consumo ADD CONSTRAINT fk_consumo_persona_externa
            FOREIGN KEY (persona_externa_id) REFERENCES persona_externa(id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.key_column_usage
                   WHERE table_schema = DATABASE() AND table_name = 'consumo'
                     AND column_name = 'persona_externa_apoderada_id'
                     AND referenced_table_name = 'persona_externa') THEN
        ALTER TABLE consumo ADD CONSTRAINT fk_consumo_persona_externa_apoderada
            FOREIGN KEY (persona_externa_apoderada_id) REFERENCES persona_externa(id);
    END IF;

    -- Exactamente uno de los dos titulares debe estar presente. Se añade al
    -- final, cuando las columnas ya existen: las filas previas tienen
    -- empleado_id NOT NULL y persona_externa_id NULL, así que la cumplen.
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE table_schema = DATABASE() AND table_name = 'consumo'
                     AND constraint_name = 'chk_consumo_titular') THEN
        ALTER TABLE consumo ADD CONSTRAINT chk_consumo_titular
            CHECK ((empleado_id IS NULL) <> (persona_externa_id IS NULL));
    END IF;
END$$
DELIMITER ;

CALL cef_v3_persona_externa();
DROP PROCEDURE cef_v3_persona_externa;
