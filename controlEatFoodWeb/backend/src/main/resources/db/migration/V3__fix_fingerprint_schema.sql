-- V3: elimina la columna template_b64 que existía en el esquema original pero que
-- nunca fue mapeada en la entidad JPA, causando fallos de INSERT en MySQL modo strict
-- (STRICT_TRANS_TABLES, por defecto en MySQL 8) porque el campo era NOT NULL sin default.
-- El template biométrico real se almacena correctamente en la columna 'template' (BLOB).
ALTER TABLE fingerprint DROP COLUMN template_b64;

