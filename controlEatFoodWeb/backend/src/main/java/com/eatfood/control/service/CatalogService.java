package com.eatfood.control.service;

import com.eatfood.control.domain.*;
import com.eatfood.control.dto.CatalogDtos.*;
import com.eatfood.control.exception.BusinessException;
import com.eatfood.control.exception.NotFoundException;
import com.eatfood.control.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final CateringRepository cateringRepository;
    private final MealTypeRepository mealTypeRepository;
    private final ScheduleRepository scheduleRepository;
    private final DeviceRepository deviceRepository;
    private final AuditService auditService;

    // ----------------- Caterings -----------------
    @Transactional(readOnly = true)
    public List<CateringResponse> listCaterings() {
        return cateringRepository.findAll().stream().map(this::toCatering).toList();
    }

    @Transactional
    public CateringResponse createCatering(CateringRequest req) {
        Catering c = Catering.builder()
                .name(req.name()).location(req.location())
                .active(req.active() == null || req.active())
                .maxDevices(req.maxDevices() == null ? 2 : req.maxDevices())
                .build();
        c = cateringRepository.save(c);
        auditService.record("Catering", String.valueOf(c.getId()), "CREATE", null, c.getName());
        return toCatering(c);
    }

    @Transactional
    public CateringResponse updateCatering(Long id, CateringRequest req) {
        Catering c = cateringRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Catering no encontrado: " + id));
        String before = c.getName() + "|location=" + c.getLocation() + "|maxDevices=" + c.getMaxDevices();
        c.setName(req.name());
        c.setLocation(req.location());
        if (req.active() != null) c.setActive(req.active());
        if (req.maxDevices() != null) c.setMaxDevices(req.maxDevices());
        c = cateringRepository.save(c);
        auditService.record("Catering", String.valueOf(id), "UPDATE", before, c.getName() + "|location=" + c.getLocation() + "|maxDevices=" + c.getMaxDevices());
        return toCatering(c);
    }

    private CateringResponse toCatering(Catering c) {
        long connected = deviceRepository.countByCateringIdAndConnectedTrue(c.getId());
        return new CateringResponse(c.getId(), c.getName(), c.getLocation(), c.isActive(), c.getMaxDevices(), connected);
    }

    // ----------------- Tipos de comida -----------------
    @Transactional(readOnly = true)
    public List<MealTypeResponse> listMealTypes() {
        return mealTypeRepository.findByActiveTrueOrderBySortOrder().stream().map(this::toMeal).toList();
    }

    @Transactional
    public MealTypeResponse createMealType(MealTypeRequest req) {
        if (mealTypeRepository.findByCode(req.code()).isPresent()) {
            throw new BusinessException("DUPLICATE", "Ya existe un tipo de comida con ese código.");
        }
        MealType m = MealType.builder()
                .code(req.code()).name(req.name())
                .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
                .active(req.active() == null || req.active())
                .build();
        m = mealTypeRepository.save(m);
        auditService.record("MealType", String.valueOf(m.getId()), "CREATE", null, m.getCode());
        return toMeal(m);
    }

    private MealTypeResponse toMeal(MealType m) {
        return new MealTypeResponse(m.getId(), m.getCode(), m.getName(), m.isActive(), m.getSortOrder());
    }

    // ----------------- Horarios -----------------
    @Transactional(readOnly = true)
    public List<ScheduleResponse> listSchedules() {
        return scheduleRepository.findAll().stream().map(this::toSchedule).toList();
    }

    @Transactional
    public ScheduleResponse upsertSchedule(ScheduleRequest req) {
        MealType meal = mealTypeRepository.findById(req.mealTypeId())
                .orElseThrow(() -> new NotFoundException("Tipo de comida no encontrado: " + req.mealTypeId()));
        if (!req.endTime().isAfter(req.startTime())) {
            throw new BusinessException("INVALID_RANGE", "La hora fin debe ser posterior a la hora inicio.");
        }
        // Reutilizar el Schedule existente para el MealType (sin importar si está activo
        // o inactivo) en lugar de crear uno nuevo: el índice UNIQUE uq_schedule_meal
        // impide tener dos filas para el mismo meal_type_id.
        Schedule s = scheduleRepository.findByMealTypeId(req.mealTypeId())
                .orElseGet(Schedule::new);
        s.setMealType(meal);
        s.setStartTime(req.startTime());
        s.setEndTime(req.endTime());
        s.setActive(req.active() == null || req.active());
        s.setUpdatedAt(java.time.OffsetDateTime.now());
        String before = s.getId() == null ? null : "anterior";
        s = scheduleRepository.save(s);
        auditService.record("Schedule", String.valueOf(s.getId()), "UPSERT", before,
                meal.getCode() + " " + req.startTime() + "-" + req.endTime());
        return toSchedule(s);
    }

    private ScheduleResponse toSchedule(Schedule s) {
        return new ScheduleResponse(s.getId(), s.getMealType().getId(), s.getMealType().getName(),
                s.getStartTime(), s.getEndTime(), s.isActive());
    }
}
