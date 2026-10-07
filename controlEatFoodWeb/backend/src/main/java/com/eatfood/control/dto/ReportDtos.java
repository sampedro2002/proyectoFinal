package com.eatfood.control.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ReportDtos {

    public record ConsumptionRow(
            Long id,
            LocalDate businessDate,
            OffsetDateTime consumedAt,
            String employeeName,
            String identityCard,
            String restaurantName,
            String mealName,
            String observation,
            boolean offline,
            String method,
            // Nombre de quien retira (empleado o persona externa); proxyExternal=true
            // cuando quien retira es una persona externa registrada.
            String proxyEmployeeName,
            boolean proxyExternal,
            boolean cancelled,
            // Grupo del titular para separar los reportes (ver PersonnelGroup):
            // NOMINA | SERVICIOS_PROFESIONALES | EXTERNO.
            String personnelGroup) {}

    /**
     * Secciones de los reportes de consumos, en el orden en que se imprimen. Se
     * clasifica por el titular (quien come), según su tipo de personal actual;
     * las personas externas van siempre en su propia sección.
     */
    public enum PersonnelGroup {
        NOMINA("Empleados de nómina"),
        SERVICIOS_PROFESIONALES("Servicios profesionales"),
        EXTERNO("Personas externas");

        private final String label;

        PersonnelGroup(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /** Valor desconocido o nulo → NOMINA, igual que el default de empleado.tipo_personal. */
        public static PersonnelGroup of(String value) {
            if (value == null) return NOMINA;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return NOMINA;
            }
        }
    }

    /** Etiqueta legible del método de registro, para UIs y reportes. */
    public static String methodLabel(String method) {
        if (method == null) return "Huella";
        return switch (method) {
            case "MANUAL"  -> "Manual";
            case "EXTERNAL" -> "MANUAL - E";
            default        -> "Huella";
        };
    }

    public record DashboardStats(
            LocalDate date,
            long totalConsumptions,
            long almuerzoCount,
            long meriendaCount,
            long expectedEmployees,
            long employeesConsumed,
            long employeesPending,
            double consumptionPercentage,
            long failedNotFound,
            long failedOutOfSchedule) {}

    public record EmployeeNotConsumed(Long employeeId, String identityCard, String fullName) {}

    public record TrendPoint(LocalDate date, long records) {}
}
