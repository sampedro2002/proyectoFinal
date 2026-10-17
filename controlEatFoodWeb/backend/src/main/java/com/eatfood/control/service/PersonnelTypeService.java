package com.eatfood.control.service;

import com.eatfood.control.domain.*;
import com.eatfood.control.dto.PersonnelTypeDtos.*;
import com.eatfood.control.exception.*;
import com.eatfood.control.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

@Service @RequiredArgsConstructor
public class PersonnelTypeService {
    private final PersonnelTypeRepository repository;
    private final EmployeeRepository employees;
    private final AuditService audit;

    @Transactional(readOnly=true)
    public List<Response> list(boolean includeInactive) {
        return (includeInactive ? repository.findAllByOrderBySortOrderAscNameAsc() : repository.findByActiveTrueOrderBySortOrderAscNameAsc())
                .stream().map(this::response).toList();
    }
    @Transactional
    public Response create(Request req) {
        String code = req.code() == null || req.code().isBlank() ? generateCode(req.name()) : req.code().trim();
        if (code.isBlank() || repository.existsByCode(code)) throw new BusinessException("DUPLICATE_PERSONNEL_TYPE", "El código de tipo de personal ya existe o no es válido.");
        var e = repository.save(PersonnelTypeEntity.builder().code(code).name(req.name().trim()).sortOrder(req.sortOrder() == null ? 0 : req.sortOrder()).build());
        audit.record("PersonnelType", e.getId().toString(), "CREATE", null, e.getCode()+"|"+e.getName());
        return response(e);
    }
    @Transactional
    public Response update(Long id, UpdateRequest req) {
        var e = repository.findById(id).orElseThrow(() -> new NotFoundException("Tipo de personal no encontrado: "+id));
        if (Boolean.FALSE.equals(req.active())) {
            if ("NOMINA".equals(e.getCode())) throw new BusinessException("PERSONNEL_TYPE_REQUIRED", "No se puede desactivar el tipo NOMINA.");
            if (employees.existsByPersonnelTypeAndStatusAndDeletedFalse(e.getCode(), EmployeeStatus.ACTIVE)) throw new BusinessException("PERSONNEL_TYPE_IN_USE", "No se puede desactivar: hay empleados activos no eliminados con este tipo de personal.");
        }
        String before=e.getName()+"|"+e.isActive()+"|"+e.getSortOrder();
        e.setName(req.name().trim()); if(req.sortOrder()!=null)e.setSortOrder(req.sortOrder()); if(req.active()!=null)e.setActive(req.active());
        e=repository.save(e); audit.record("PersonnelType",e.getId().toString(),"UPDATE",before,e.getName()+"|"+e.isActive()+"|"+e.getSortOrder()); return response(e);
    }
    public static String generateCode(String name) {
        String normalized=Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).trim().replaceAll("\\s+", "_").replaceAll("[^A-Z0-9_]", "");
        return normalized.substring(0, Math.min(30, normalized.length()));
    }
    private Response response(PersonnelTypeEntity e){ return new Response(e.getId(),e.getCode(),e.getName(),e.getSortOrder(),e.isActive()); }
}
