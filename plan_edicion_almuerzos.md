# Plan de Acción: Edición de Todos los Consumos (Manuales y Huella)

Este plan detalla los pasos exactos que un agente debe seguir para modificar el sistema (Backend, Web y Móvil) de modo que se puedan ver **todos** los consumos registrados (incluyendo huellas) en la pantalla de edición, permitiendo cancelar/reactivar los de huella, pero bloqueando la edición de sus detalles (titular, comida, restaurante, etc.).

## 1. Modificaciones en el Backend (Spring Boot)

**Objetivo:** Permitir que la API que lista los consumos para edición devuelva **todos** los métodos (MANUAL, EXTERNAL, FINGERPRINT), pero mantener la restricción de que los de FINGERPRINT no se pueden alterar en sus detalles.

1. **`ConsumptionRepository.java`**
   - Localizar el método `findManualConsumptions`.
   - Eliminar de la consulta principal y del `countQuery` la restricción de método:
     `- WHERE c.method IN (com.eatfood.control.domain.Method.MANUAL, com.eatfood.control.domain.Method.EXTERNAL)`
     Esto hará que la consulta devuelva también los consumos registrados con `FINGERPRINT`.
   - (Opcional) Renombrar el método a `findConsumptionsForEdit` para mayor claridad.

2. **`ManualConsumptionService.java`**
   - Si se renombró el método en el repositorio, actualizar la llamada en `listManual`.
   - **Verificar la lógica de `update`**: El método `update` actualmente ya tiene una validación: `if (c.getMethod() != Method.MANUAL && c.getMethod() != Method.EXTERNAL) throw new BusinessException(...)`. **Mantener esta validación**; es la protección a nivel de API para que nadie intente editar los detalles de un consumo por huella.
   - **Verificar `cancel` y `uncancel`**: Estos métodos no restringen por método de registro actualmente, por lo que funcionarán correctamente para los consumos de `FINGERPRINT`.

3. **`ManualScanController.java`**
   - Actualizar la descripción del endpoint `@GetMapping` (list) en Swagger para indicar que ahora lista "todos los consumos (manuales y huellas)" para edición.

## 2. Modificaciones en el Frontend Web (React)

**Objetivo:** Mostrar los consumos de huella en la tabla y deshabilitar el botón "Editar" para ellos, permitiendo solo "Cancelar" o "Reactivar".

1. **`EditManualConsumptions.jsx`**
   - Cambiar los títulos en la interfaz de "Editar Consumos (Manuales)" a "Editar Consumos" o "Editar Consumos del Día".
   - En el renderizado de la tabla (dentro del `<tbody>`), agregar la lógica para mostrar un badge cuando el método es huella:
     ```jsx
     {r.method === 'FINGERPRINT' && <span className="badge">Huella</span>}
     ```
   - En la celda de acciones `<td>`, modificar la condición del botón "Editar" para que solo aparezca si no es huella:
     ```jsx
     {!r.cancelled && r.method !== 'FINGERPRINT' && (
       <button className="ghost" onClick={() => openEdit(r)}>Editar</button>
     )}
     ```
   - Los botones "Cancelar" y "Reactivar" ya están condicionados solo por `!r.cancelled` y `r.cancelled`, por lo que funcionarán y se mostrarán automáticamente para los de huella.
   - (Opcional) Renombrar el archivo a `EditConsumptions.jsx` y actualizar las rutas en `App.jsx` y el menú en `Dashboard.jsx`.

## 3. Modificaciones en el Frontend Móvil (Kotlin / Android)

**Objetivo:** Replicar el mismo comportamiento del frontend web en la aplicación móvil de administradores.

1. **`AdminScreens.kt`** (o donde esté la pantalla de "Editar consumos manuales")
   - Cambiar el título de la pantalla en la TopBar a "Editar consumos del día".
   - En la tarjeta (`Card`) que representa cada consumo en la lista (LazyColumn):
     - Mostrar un indicador visual (Texto o Chip) si el método es `FINGERPRINT` ("Huella").
     - Ubicar el IconButton de edición (el lápiz) y envolverlo en una condición:
       ```kotlin
       if (consumption.method != "FINGERPRINT") {
           IconButton(onClick = { /* Abrir edición */ }) { ... }
       }
       ```
     - Asegurarse de que el botón de Cancelar/Reactivar siga visible y funcional para todos los ítems.

Con estos cambios, el sistema cumplirá exactamente con el requerimiento: los administradores verán los almuerzos registrados por huella, no podrán editar a quién le pertenecen ni qué restaurante fue, pero sí podrán cancelarlos (si fue un error) o reactivarlos.
