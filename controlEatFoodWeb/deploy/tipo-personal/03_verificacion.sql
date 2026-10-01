-- Solo lectura. Ejecutar despues de V3 y comparar con la prevalidacion.
SELECT DATABASE() AS base_actual;
SELECT column_name, column_type, is_nullable, column_default, extra
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'empleado'
  AND column_name = 'tipo_personal';

SELECT tipo_personal, COUNT(*) AS cantidad
FROM empleado GROUP BY tipo_personal;

SELECT COUNT(*) AS categorias_invalidas FROM empleado
WHERE tipo_personal IS NULL
   OR BINARY tipo_personal NOT IN (BINARY 'NOMINA', BINARY 'SERVICIOS_PROFESIONALES');

SELECT 'empleado' AS tabla, COUNT(*) AS registros FROM empleado
UNION ALL SELECT 'huella_digital', COUNT(*) FROM huella_digital
UNION ALL SELECT 'consumo', COUNT(*) FROM consumo;

SELECT COUNT(*) AS huellas_sin_empleado
FROM huella_digital h LEFT JOIN empleado e ON e.id = h.empleado_id
WHERE e.id IS NULL;

-- Antes del nuevo backend puede no existir V3 en el historial: es normal.
-- Tras desplegar, debe aparecer aplicada correctamente por Flyway.
SELECT version, description, script, success
FROM flyway_schema_history WHERE version = '3';
