package com.eatfood.control.service;

import com.eatfood.control.domain.Employee;
import com.eatfood.control.domain.PersonnelType;
import com.eatfood.control.dto.EmployeeDtos.EmployeeRequest;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.FingerprintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeePersonnelTypeTest {
    @Mock private EmployeeRepository employees;
    @Mock private FingerprintRepository fingerprints;
    @Mock private AuditService audit;
    @InjectMocks private EmployeeService service;

    private EmployeeRequest request(PersonnelType type) {
        return new EmployeeRequest("TEST-123", "Persona Prueba", null, true,
                null, null, null, type);
    }

    private Employee professional() {
        return Employee.builder().id(7L).identityCard("TEST-123").fullName("Persona Prueba")
                .personnelType(PersonnelType.SERVICIOS_PROFESIONALES).allowsSnack(true).build();
    }

    @BeforeEach
    void saveSameEntity() {
        when(employees.save(any(Employee.class))).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            if (e.getId() == null) e.setId(7L);
            return e;
        });
    }

    @Test
    void oldClientCreatesPayrollEmployee() {
        var result = service.create(request(null));
        assertThat(result.personnelType()).isEqualTo(PersonnelType.NOMINA);
        verify(audit).record(eq("Employee"), eq("7"), eq("CREATE"), isNull(), contains("tipoPersonal=NOMINA"));
    }

    @Test
    void createsProfessionalWithoutChangingMealPermissions() {
        var result = service.create(request(PersonnelType.SERVICIOS_PROFESIONALES));
        assertThat(result.personnelType()).isEqualTo(PersonnelType.SERVICIOS_PROFESIONALES);
        assertThat(result.allowsLunch()).isTrue();
        assertThat(result.allowsSnack()).isFalse();
    }

    @Test
    void legacyUpdatePreservesProfessionalCategoryIdentityAndFingerprintCount() {
        Employee existing = professional();
        when(employees.findById(7L)).thenReturn(Optional.of(existing));
        when(fingerprints.countByEmployeeIdAndActiveTrue(7L)).thenReturn(3L);
        var result = service.update(7L, request(null));
        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.personnelType()).isEqualTo(PersonnelType.SERVICIOS_PROFESIONALES);
        assertThat(result.fingerprintCount()).isEqualTo(3);
        assertThat(result.allowsSnack()).isTrue();
        verify(fingerprints).countByEmployeeIdAndActiveTrue(7L);
        verifyNoMoreInteractions(fingerprints);
    }

    @Test
    void legacyReactivationPreservesCategoryAndExistingRow() {
        Employee existing = professional();
        existing.setDeleted(true);
        when(employees.findByIdentityCard("TEST-123")).thenReturn(Optional.of(existing));
        var result = service.create(request(null));
        assertThat(result.id()).isEqualTo(7L);
        assertThat(existing.isDeleted()).isFalse();
        assertThat(result.personnelType()).isEqualTo(PersonnelType.SERVICIOS_PROFESIONALES);
        verify(employees).save(existing);
        verify(audit).record(eq("Employee"), eq("7"), eq("REACTIVATE"),
                contains("tipoPersonal=SERVICIOS_PROFESIONALES"), contains("tipoPersonal=SERVICIOS_PROFESIONALES"));
    }

    @Test
    void explicitCategoryChangeRecordsBeforeAndAfter() {
        Employee existing = professional();
        when(employees.findById(7L)).thenReturn(Optional.of(existing));
        var result = service.update(7L, request(PersonnelType.NOMINA));
        assertThat(result.personnelType()).isEqualTo(PersonnelType.NOMINA);
        ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("Employee"), eq("7"), eq("UPDATE"), before.capture(), after.capture());
        assertThat(before.getValue()).contains("tipoPersonal=SERVICIOS_PROFESIONALES");
        assertThat(after.getValue()).contains("tipoPersonal=NOMINA");
    }
}
