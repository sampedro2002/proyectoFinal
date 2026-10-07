
## Paso 1 — Respaldo de la base

Respaldo de la base de datos.

---

## Paso 2 — Revisar el historial de Flyway

En Workbench, conectado a la base de producción:

```sql
USE control_almuerzos;

SELECT installed_rank, version, description, type, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

Comparar con esta tabla:

| Resultado | Caso | Qué hacer |
|---|---|---|
| V1 = `-449241297` y V2 = `-650024853` | **A** (el más común) | Hacer pasos 3 y 4, luego 5 |
| V1 = `2016753051` y V2 = `-650024853` | **B** | Saltar pasos 3 y 4, ir al 5 |
| Una sola fila `<< Flyway Baseline >>` en versión 2 | **C** | Saltar pasos 3 y 4, ir al 5 |
| Ya aparecen V3 y/o V4 | — | Ya está actualizado. Solo pasos 5 y 6 si falta el código |
| Otro checksum, o alguna fila con `success = 0` | **D** | **Detenerse y avisar.** No arrancar el backend |

Ejemplo real del caso A (producción, 2026-10-07):

```
installed_rank,version,description,type,checksum,success
1,1,schema,SQL,-449241297,1
2,2,seed,SQL,-650024853,1
```

---

## Paso 3 — Confirmar la estructura (solo caso A)

```sql
SHOW TABLES LIKE 'persona_externa';
SHOW COLUMNS FROM consumo LIKE 'persona_externa%';
```

Debe salir **la tabla** y **2 columnas** (`persona_externa_id` y
`persona_externa_apoderada_id`). Si no aparecen, **detenerse y avisar**.

---

## Paso 4 — Alinear el checksum de V1 (solo caso A)

Ejecutar **la sentencia completa, con su `WHERE`**, en una sola ejecución
(seleccionar todo el texto antes de pulsar ejecutar):

```sql
UPDATE flyway_schema_history SET checksum = 2016753051 WHERE version = '1' AND checksum = -449241297;
```

- Debe decir **`1 row affected`**.
- `0 rows affected` → detenerse y avisar.
- Si Workbench tiene *autocommit* desactivado, ejecutar también `COMMIT;`.

Verificar:

```sql
SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank;
```

Debe quedar **exactamente**:

```
1 | 2016753051
2 | -650024853
```

> ⚠️ Error ya visto en pruebas: se ejecutó el `UPDATE` **sin el `WHERE`** y
> cambió también V2. El backend falla con
> `Migration checksum mismatch for migration version 2 / Applied to database : 2016753051`.
> Se corrige con:
> ```sql
> UPDATE flyway_schema_history SET checksum = -650024853 WHERE version = '2';
> ```

---

## Paso 5 — Actualizar el código y el servicio

