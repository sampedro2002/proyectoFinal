package com.eatfood.control.web;

import com.eatfood.control.dto.CatalogDtos.*;
import com.eatfood.control.service.CatalogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Catálogos (cargos, caterings, comidas, horarios)")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    // ---- Cargos ----
    @GetMapping("/positions")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public List<PositionResponse> listPositions() {
        return catalogService.listPositions();
    }

    @PostMapping("/positions")
    @PreAuthorize("hasRole('ADMIN')")
    public PositionResponse createPosition(@Valid @RequestBody PositionRequest req) {
        return catalogService.createPosition(req);
    }

    @PutMapping("/positions/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PositionResponse updatePosition(@PathVariable Long id, @Valid @RequestBody PositionRequest req) {
        return catalogService.updatePosition(id, req);
    }

    // ---- Caterings ----
    @GetMapping("/caterings")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public List<CateringResponse> listCaterings() {
        return catalogService.listCaterings();
    }

    @PostMapping("/caterings")
    @PreAuthorize("hasRole('ADMIN')")
    public CateringResponse createCatering(@Valid @RequestBody CateringRequest req) {
        return catalogService.createCatering(req);
    }

    @PutMapping("/caterings/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CateringResponse updateCatering(@PathVariable Long id, @Valid @RequestBody CateringRequest req) {
        return catalogService.updateCatering(id, req);
    }

    // ---- Tipos de comida ----
    @GetMapping("/meal-types")
    public List<MealTypeResponse> listMealTypes() {
        return catalogService.listMealTypes();
    }

    @PostMapping("/meal-types")
    @PreAuthorize("hasRole('ADMIN')")
    public MealTypeResponse createMealType(@Valid @RequestBody MealTypeRequest req) {
        return catalogService.createMealType(req);
    }

    // ---- Horarios ----
    @GetMapping("/schedules")
    public List<ScheduleResponse> listSchedules() {
        return catalogService.listSchedules();
    }

    @PostMapping("/schedules")
    @PreAuthorize("hasRole('ADMIN')")
    public ScheduleResponse upsertSchedule(@Valid @RequestBody ScheduleRequest req) {
        return catalogService.upsertSchedule(req);
    }
}
