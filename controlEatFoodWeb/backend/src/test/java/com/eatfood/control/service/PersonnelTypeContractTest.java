package com.eatfood.control.service;

import com.eatfood.control.domain.Employee;
import com.eatfood.control.dto.EmployeeDtos.EmployeeRequest;
import com.eatfood.control.dto.EmployeeDtos.EmployeeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PersonnelTypeContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void omittedCategoryRemainsNullInOldRequests() throws Exception {
        var req = mapper.readValue("""
                {"identityCard":"TEST-123","fullName":"Persona Prueba","isPassport":true}
                """, EmployeeRequest.class);
        assertThat(req.personnelType()).isNull();
        assertThat(new Employee().getPersonnelType()).isEqualTo("NOMINA");
        assertThat(Employee.builder().build().getPersonnelType()).isEqualTo("NOMINA");
    }

    @Test
    void personnelTypeIsAnOpenStringInRequests() throws Exception {
        var req = mapper.readValue("{\"personnelType\":\"CUSTOM\"}", EmployeeRequest.class);
        assertThat(req.personnelType()).isEqualTo("CUSTOM");
    }

    @Test
    void exportsAppendCategoryWithoutMovingExistingColumns() throws Exception {
        var person = new EmployeeResponse(7L, "TEST-123", "Persona Prueba", "Observación",
                "ACTIVE", true, false, false, 2, "SERVICIOS_PROFESIONALES");
        ExportService export = new ExportService();
        String csv = new String(export.employeesToCsv(List.of(person)), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines[0].split(";")).hasSize(8);
        assertThat(lines[0]).endsWith("Tipo de personal");
        assertThat(lines[1]).contains("Servicios profesionales");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(export.employeesToExcel(List.of(person))))) {
            var sheet = workbook.getSheetAt(0);
            var row = sheet.getRow(sheet.getLastRowNum());
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("TEST-123");
            assertThat(row.getCell(5).getNumericCellValue()).isEqualTo(2);
            assertThat(row.getCell(7).getStringCellValue()).isEqualTo("Servicios profesionales");
        }
    }
}
