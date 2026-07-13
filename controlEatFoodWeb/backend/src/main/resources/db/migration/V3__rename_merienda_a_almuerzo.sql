-- ============================================================================
-- El sistema maneja únicamente dos platos por día: Desayuno y Almuerzo.
-- El segundo plato se llamaba "Merienda" en versiones anteriores; se renombran
-- los consumos históricos para que reportes, dashboard y kiosk cuenten bien.
-- ============================================================================

UPDATE consumption SET meal_name = 'Almuerzo' WHERE meal_name = 'Merienda';
