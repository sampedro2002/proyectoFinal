-- V10: Corrección de zona horaria.
--
-- Contexto: el contenedor MySQL corre en UTC y, además, Hibernate 6 guardaba las
-- columnas OffsetDateTime con estrategia NORMALIZE_UTC (ignorando hibernate.jdbc.time_zone).
-- Resultado: todas las fechas/horas quedaron almacenadas en UTC, es decir 5 horas
-- ADELANTADAS respecto a la hora local de Ecuador (America/Guayaquil, UTC-5), mientras
-- que business_date sí quedó en fecha local. Eso provocaba que el kiosco y los reportes
-- mostraran una hora que no correspondía.
--
-- A partir de esta versión la app usa hibernate.timezone.default_storage=NORMALIZE, por lo
-- que escribe/lee en hora local. Esta migración convierte los datos YA existentes (en UTC)
-- a hora local restándoles 5 horas, para que queden consistentes con el nuevo comportamiento.
--
-- Ecuador no aplica horario de verano: el offset es fijo UTC-5.

UPDATE app_config     SET updated_at   = updated_at   - INTERVAL 5 HOUR;

UPDATE app_user       SET locked_until = locked_until - INTERVAL 5 HOUR WHERE locked_until IS NOT NULL;
UPDATE app_user       SET created_at   = created_at   - INTERVAL 5 HOUR,
                          updated_at   = updated_at   - INTERVAL 5 HOUR;

UPDATE audit_log      SET created_at   = created_at   - INTERVAL 5 HOUR;

UPDATE consumption    SET consumed_at  = consumed_at  - INTERVAL 5 HOUR,
                          created_at   = created_at   - INTERVAL 5 HOUR;

UPDATE device         SET last_seen    = last_seen    - INTERVAL 5 HOUR WHERE last_seen IS NOT NULL;
UPDATE device         SET created_at   = created_at   - INTERVAL 5 HOUR;

UPDATE employee       SET created_at   = created_at   - INTERVAL 5 HOUR,
                          updated_at   = updated_at   - INTERVAL 5 HOUR;

UPDATE failed_scan    SET occurred_at  = occurred_at  - INTERVAL 5 HOUR;

UPDATE fingerprint    SET enrolled_at  = enrolled_at  - INTERVAL 5 HOUR;

UPDATE login_session  SET issued_at    = issued_at    - INTERVAL 5 HOUR,
                          expires_at   = expires_at   - INTERVAL 5 HOUR;

UPDATE restaurant     SET created_at   = created_at   - INTERVAL 5 HOUR,
                          updated_at   = updated_at   - INTERVAL 5 HOUR;

UPDATE schedule       SET updated_at   = updated_at   - INTERVAL 5 HOUR;
