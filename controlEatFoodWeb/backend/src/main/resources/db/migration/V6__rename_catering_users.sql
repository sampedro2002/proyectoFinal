-- ============================================================================
-- V6: Renombra los usuarios de catering desde el esquema "catering<id>" al
-- esquema "catering<Nombre>" derivado del nombre del catering.
-- Ej: catering1 -> cateringNorte, catering2 -> cateringCentro, catering3 -> cateringSur.
-- La contraseña (catering123) no cambia; el rename preserva user_id, roles y
-- catering_id, de modo que las sesiones y dispositivos asociados siguen validos.
-- ============================================================================

UPDATE app_user u
JOIN catering c ON u.catering_id = c.id
SET u.username = CONCAT(
        LCASE(LEFT(REPLACE(c.name, ' ', ''), 1)),
        SUBSTRING(REPLACE(c.name, ' ', ''), 2)
    )
WHERE u.username REGEXP '^catering[0-9]+$';
