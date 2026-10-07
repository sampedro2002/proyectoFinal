package com.eatfood.control.service;

import com.eatfood.control.dto.ReportDtos.ConsumptionRow;
import com.eatfood.control.dto.ReportDtos.PersonnelGroup;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reportes separados por tipo de personal: nómina, servicios profesionales y
 * personas externas, cada uno con su subtotal, y el total general al final.
 */
class ExportServiceSectionsTest {

    private final ExportService exportService = new ExportService();

    private static ConsumptionRow row(long id, String meal, String group) {
        return new ConsumptionRow(id, LocalDate.of(2026, 10, 7), OffsetDateTime.now(),
                "Persona " + id, "CI-" + id, "Comedor", meal, null, false,
                "FINGERPRINT", null, false, false, group);
    }

    private final List<ConsumptionRow> rows = List.of(
            row(1, "Almuerzo", "EXTERNO"),
            row(2, "Almuerzo", "SERVICIOS_PROFESIONALES"),
            row(3, "Merienda", "NOMINA"),
            row(4, "Almuerzo", "NOMINA"),
            row(5, "Almuerzo", null));   // sin tipo → nómina

    @Test
    void sections_agrupaEnOrdenNominaServiciosExternos() {
        var sections = ExportService.sections(rows);

        assertThat(sections).extracting(ExportService.Section::group).containsExactly(
                PersonnelGroup.NOMINA, PersonnelGroup.SERVICIOS_PROFESIONALES, PersonnelGroup.EXTERNO);
        assertThat(sections.get(0).rows()).extracting(ConsumptionRow::id).containsExactly(3L, 4L, 5L);
        assertThat(sections.stream().mapToInt(s -> s.rows().size()).sum()).isEqualTo(rows.size());
    }

    @Test
    void sections_omiteGruposVacios() {
        var sections = ExportService.sections(List.of(row(1, "Almuerzo", "NOMINA")));
        assertThat(sections).extracting(ExportService.Section::group).containsExactly(PersonnelGroup.NOMINA);
    }

    @Test
    void csv_incluyeTipoDePersonalSubtotalesYTotal() {
        String csv = new String(exportService.toCsv(rows, "Periodo"), StandardCharsets.UTF_8);

        assertThat(csv).contains(";Tipo de personal");
        assertThat(csv).contains("Empleados de nómina;Total;3");
        assertThat(csv).contains("Servicios profesionales;Total;1");
        assertThat(csv).contains("Personas externas;Total;1");
        assertThat(csv).contains("TOTAL:;5");
    }

    @Test
    void pdfYExcel_seGeneranConSecciones() {
        assertThat(exportService.toPdf(rows, "Periodo")).isNotEmpty();
        assertThat(exportService.toExcel(rows, "Periodo")).isNotEmpty();
        assertThat(exportService.toPdf(List.of(), "Periodo")).isNotEmpty();
        assertThat(exportService.kioskDailyPdf("Comedor", LocalDate.of(2026, 10, 7), rows,
                java.util.Map.of("Almuerzos", 4L, "Meriendas", 1L))).isNotEmpty();
    }
}
