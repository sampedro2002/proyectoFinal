-- ----------------------------------------------------------------------------
-- Renombre general de los platos (julio 2026):
--   1er plato: 'Desayuno' -> 'Almuerzo'
--   2º plato:  'Almuerzo' -> 'Merienda'
-- El orden de los UPDATE es crítico: primero se libera el nombre 'Almuerzo'
-- (pasando los registros existentes a 'Merienda') y recién entonces los
-- antiguos 'Desayuno' pueden tomar el nombre 'Almuerzo'. Invertir el orden
-- fusionaría ambos platos en uno solo.
-- ----------------------------------------------------------------------------
UPDATE consumption SET meal_name = 'Merienda' WHERE meal_name = 'Almuerzo';
UPDATE consumption SET meal_name = 'Almuerzo' WHERE meal_name = 'Desayuno';
