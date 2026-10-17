package com.eatfood.control.service;

import com.eatfood.control.domain.PersonnelTypeEntity;
import com.eatfood.control.dto.PersonnelTypeDtos.Request;
import com.eatfood.control.dto.PersonnelTypeDtos.UpdateRequest;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.repository.EmployeeRepository;
import com.eatfood.control.repository.PersonnelTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PersonnelTypeServiceTest {
    @Mock PersonnelTypeRepository types;
    @Mock EmployeeRepository employees;
    @Mock AuditService audit;
    @InjectMocks PersonnelTypeService service;

    @Test void createsTypeAndBuildsNormalizedCode() {
        when(types.existsByCode("NUEVO_TIPO")).thenReturn(false);
        when(types.save(any())).thenAnswer(call -> { PersonnelTypeEntity e=call.getArgument(0); e.setId(8L); return e; });
        var response=service.create(new Request("Nuevo Tipo", null, 3));
        assertThat(response.code()).isEqualTo("NUEVO_TIPO");
        assertThat(response.sortOrder()).isEqualTo(3);
        verify(audit).record("PersonnelType","8","CREATE",null,"NUEVO_TIPO|Nuevo Tipo");
    }

    @Test void rejectsDuplicateCode() {
        when(types.existsByCode("NOMINA")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new Request("Nómina", "NOMINA", null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("código");
        verify(types, never()).save(any());
    }

    @Test void refusesDisableWhenActiveEmployeesUseType() {
        var type=PersonnelTypeEntity.builder().id(3L).code("SERVICIOS").name("Servicios").active(true).build();
        when(types.findById(3L)).thenReturn(Optional.of(type));
        when(employees.existsByPersonnelTypeAndStatusAndDeletedFalse(eq("SERVICIOS"), any())).thenReturn(true);
        assertThatThrownBy(() -> service.update(3L,new UpdateRequest("Servicios",null,false)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("empleados activos");
        verify(types,never()).save(any());
    }

    @Test void generatedCodeRemovesDiacriticsAndLimitsLength() {
        assertThat(PersonnelTypeService.generateCode("  Servicios Públicos  ")).isEqualTo("SERVICIOS_PUBLICOS");
        assertThat(PersonnelTypeService.generateCode("1234567890123456789012345678901")).hasSize(30);
    }
}
