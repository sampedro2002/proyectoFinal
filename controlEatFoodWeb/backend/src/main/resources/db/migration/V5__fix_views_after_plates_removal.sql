-- ============================================================================
-- V5: Recrea las vistas que referenciaban columnas eliminadas en V4
-- (employee.allowed_plates, position.default_plates, consumption.plates).
-- Sin esta migración, cualquier SELECT sobre las vistas devolvería un error
-- "View ... references invalid column(s)".
-- ============================================================================

DROP VIEW IF EXISTS v_daily_consumption;
DROP VIEW IF EXISTS v_employee_effective_config;

CREATE VIEW v_daily_consumption AS
SELECT c.business_date,
       c.catering_id,
       mt.code         AS meal_code,
       COUNT(*)        AS total_records
FROM consumption c
JOIN meal_type mt ON mt.id = c.meal_type_id
GROUP BY c.business_date, c.catering_id, mt.code;

CREATE VIEW v_employee_effective_config AS
SELECT e.id AS employee_id,
       e.full_name,
       e.allows_lunch,
       (e.allows_snack OR COALESCE(p.allows_snack, FALSE)) AS effective_snack
FROM employee e
LEFT JOIN `position` p ON p.id = e.position_id
WHERE e.deleted = FALSE;
