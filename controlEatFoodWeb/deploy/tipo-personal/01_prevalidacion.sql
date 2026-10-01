-- Solo lectura. Seleccionar la base de produccion correcta antes de ejecutar.
SELECT DATABASE() AS base_actual, VERSION() AS version_mysql;
SHOW CREATE TABLE empleado;
SHOW GRANTS;

SELECT table_name, engine, table_rows, data_length, index_length
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('empleado', 'huella_digital', 'consumo');

SELECT column_name, column_type, is_nullable, column_default, extra
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'empleado'
  AND column_name = 'tipo_personal';

SELECT 'empleado' AS tabla, COUNT(*) AS registros FROM empleado
UNION ALL SELECT 'huella_digital', COUNT(*) FROM huella_digital
UNION ALL SELECT 'consumo', COUNT(*) FROM consumo;

SELECT COUNT(*) AS huellas_sin_empleado
FROM huella_digital h LEFT JOIN empleado e ON e.id = h.empleado_id
WHERE e.id IS NULL;

-- Si esta tabla no existe, revisar el baseline de la instalacion antes de seguir.
SELECT installed_rank, version, description, script, success
FROM flyway_schema_history ORDER BY installed_rank;
