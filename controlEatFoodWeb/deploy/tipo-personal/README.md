# Despliegue: tipo de personal

La categoria se almacena en `empleado.tipo_personal` como `VARCHAR(30) NOT NULL DEFAULT 'NOMINA'`.
Valores admitidos por la API: `NOMINA` y `SERVICIOS_PROFESIONALES`.
Las personas existentes reciben NOMINA al crear la columna. Reclasificar a los profesionales
desde el formulario de empleados despues del despliegue. No inferir la categoria por sus huellas.

## Orden de ejecucion

1. Probar estos archivos en una copia con la misma version de MySQL, incluyendo ejecutar V4
   dos veces y arrancar despues el nuevo backend. Comprobar que rechaza una columna incompatible.
2. Respaldar la base y comprobar su restauracion; conservar la configuracion de cifrado
   necesaria para leer las huellas. Seleccionar explicitamente la base correcta en el cliente SQL.
3. Ejecutar [01_prevalidacion.sql](01_prevalidacion.sql) y guardar el resultado.
   Revisar la version, estructura, motor y el historial Flyway. Este repositorio parte de V1/V2/V3 (persona_externa):
   si produccion tiene otra V4 o versiones posteriores, detenerse y reconciliar el historial.
   No borrar filas del historial ni usar `repair` para ocultar diferencias.
4. En un horario de baja actividad, ejecutar el archivo completo
   [V4__tipo_personal.sql](../../backend/src/main/resources/db/migration/V4__tipo_personal.sql).
   Este es el script de cambio manual y la migracion de Flyway: no hay una segunda copia.
   Usar MySQL Workbench o el cliente `mysql`, que interpretan `DELIMITER`.
   Configurar el cliente para detenerse ante errores; no usar `--force`.
5. Ejecutar [03_verificacion.sql](03_verificacion.sql). Debe haber cero categorias invalidas
   y ninguna nueva huella huerfana. Comparar conteos teniendo en cuenta las altas/consumos
   realizados mientras el sistema seguia funcionando. Los conteos no demuestran por si solos
   la igualdad de los datos: verificar en la copia que se mantienen IDs y plantillas binarias.
6. Desplegar backend y frontend juntos. Mantener `ddl-auto: validate`, Flyway habilitado,
   `FLYWAY_CLEAN_ON_START=false` y `FLYWAY_CLEAN_DISABLED=true`.
   Flyway ejecutara V4, comprobara la columna existente y registrara la migracion.
   No insertar manualmente una fila en `flyway_schema_history`.
7. Repetir la verificacion: V3 (persona_externa) y V4 (tipo_personal) deben tener `success=1`. Validar lectura biometrica, registro
   de consumos, alta/edicion de ambas categorias, CSV/Excel y edicion desde clientes antiguos.

## Comportamiento y permisos del SQL

V4 crea un procedimiento auxiliar `cef_v3_tipo_personal`, lo ejecuta y lo elimina.
Si falla, no continuar con el despliegue: revisar el mensaje y el esquema real. Un reintento
limpia ese procedimiento auxiliar y vuelve a verificar el estado sin reclasificar personas.
No ejecutar el script manual y el arranque de Flyway al mismo tiempo.

Tanto la cuenta que ejecuta el SQL manual como la que aplica Flyway necesitan `SELECT`,
`ALTER`, `CREATE ROUTINE`, `ALTER ROUTINE` y `EXECUTE` sobre la base destino.
No se conceden permisos desde estos archivos.

El script deja que MySQL elija el algoritmo del ALTER; puede ser instantaneo o reconstruir
la tabla segun la version y su estructura. No se ha confirmado la version de produccion.
El limite de espera por metadatos es 15 segundos y se restaura al terminar o fallar;
no es un limite de duracion del ALTER. No editar V4 despues de su primera aplicacion.
Los cambios DDL no deben tratarse como una transaccion reversible con ROLLBACK.

## Compatibilidad y alcance

- Una solicitud de alta que omita `personnelType` recibe NOMINA.
- Una solicitud de edicion o reactivacion que omita el campo conserva la categoria.
- El selector no altera huellas, permisos de comida, estado activo ni metodo de registro.
- La categoria se muestra en el listado y al final de las exportaciones CSV/Excel de empleados.
- No se trasladan personas entre `empleado` y `persona_externa`.
- Los indicadores de esperados y pendientes conservan su calculo actual sobre personas activas.
- La categoria representa la situacion actual; no es un historial laboral por fecha de consumo.
- Si hay que revertir la aplicacion, conservar la columna y sus datos al volver al backend anterior.

## Validacion pendiente

No se ejecutaron SQL, compilaciones ni pruebas durante la preparacion, por indicacion del usuario.
Se incluyen pruebas de regresion de categoria por defecto, compatibilidad de solicitudes antiguas,
reactivacion, auditoria y exportaciones. Ejecutarlas en el entorno de pruebas antes del despliegue.

Referencias: [Flyway y DELIMITER](https://documentation.red-gate.com/fd/mysql-277579322.html),
[permisos de procedimientos MySQL](https://dev.mysql.com/doc/mysql/8.0/en/create-procedure.html),
[algoritmos DDL MySQL](https://dev.mysql.com/doc/refman/8.0/en/innodb-online-ddl-operations.html).
