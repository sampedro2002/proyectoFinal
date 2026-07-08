package com.eatfood.control.web;

import com.eatfood.control.dto.CatalogDtos.*;
import com.eatfood.control.service.CatalogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Catálogos (restaurants, comidas, horarios)")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    // ---- Restaurants ----
    @GetMapping("/restaurants")
    @PreAuthorize("hasRole('ADMIN')")
    public List<RestaurantResponse> listRestaurants() {
        return catalogService.listRestaurants();
    }

    @PostMapping("/restaurants")
    @PreAuthorize("hasRole('ADMIN')")
    public RestaurantResponse createRestaurant(@Valid @RequestBody RestaurantRequest req) {
        return catalogService.createRestaurant(req);
    }

    @PutMapping("/restaurants/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public RestaurantResponse updateRestaurant(@PathVariable Long id, @Valid @RequestBody RestaurantRequest req) {
        return catalogService.updateRestaurant(id, req);
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
