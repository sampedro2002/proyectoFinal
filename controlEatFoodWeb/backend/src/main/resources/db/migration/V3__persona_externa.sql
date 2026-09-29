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
-- ============================================================================

CREATE TABLE IF NOT EXISTS persona_externa (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    cedula            VARCHAR(20) NOT NULL UNIQUE,
    nombre_completo   VARCHAR(160) NOT NULL,
    observacion       VARCHAR(500),
    creado_en         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------------------
-- consumo: titular empleado O persona externa
-- ----------------------------------------------------------------------------

-- empleado_id deja de ser obligatorio: es NULL en los consumos cuyo titular es
-- una persona externa (metodo='EXTERNAL').
ALTER TABLE consumo
    MODIFY COLUMN empleado_id BIGINT NULL;

ALTER TABLE consumo
    ADD COLUMN persona_externa_id BIGINT NULL AFTER empleado_id;

-- Quien retira (apoderado), opcional: empleado (empleado_apoderado_id) O
-- persona externa registrada (persona_externa_apoderada_id). Nunca ambos: lo
-- valida ScanService/ManualConsumptionService, no hay CHECK en BD porque MySQL
-- prohíbe un CHECK sobre una columna usada en una FK con ON DELETE SET NULL
-- (error 3823), y empleado_apoderado_id es justamente así. La exclusión es
-- 100% responsabilidad del código.
ALTER TABLE consumo
    ADD COLUMN persona_externa_apoderada_id BIGINT NULL AFTER empleado_apoderado_id;

ALTER TABLE consumo
    ADD KEY idx_consumo_externo_fecha (persona_externa_id, fecha_negocio),
    ADD KEY idx_consumo_apoderado_externo (persona_externa_apoderada_id);

ALTER TABLE consumo
    ADD CONSTRAINT fk_consumo_persona_externa
        FOREIGN KEY (persona_externa_id) REFERENCES persona_externa(id),
    ADD CONSTRAINT fk_consumo_persona_externa_apoderada
        FOREIGN KEY (persona_externa_apoderada_id) REFERENCES persona_externa(id);

-- Exactamente uno de los dos titulares debe estar presente. Se añade al final,
-- cuando las columnas ya existen: las filas previas tienen empleado_id NOT NULL
-- y persona_externa_id NULL, así que la cumplen.
ALTER TABLE consumo
    ADD CONSTRAINT chk_consumo_titular
        CHECK ((empleado_id IS NULL) <> (persona_externa_id IS NULL));
