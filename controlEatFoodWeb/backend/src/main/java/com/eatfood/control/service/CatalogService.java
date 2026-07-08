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

    private final RestaurantRepository restaurantRepository;
    private final ScheduleRepository scheduleRepository;
    private final DeviceRepository deviceRepository;
    private final AuditService auditService;

    // ----------------- Restaurants -----------------
    @Transactional(readOnly = true)
    public List<RestaurantResponse> listRestaurants() {
        return restaurantRepository.findAll().stream().map(this::toRestaurant).toList();
    }

    @Transactional
    public RestaurantResponse createRestaurant(RestaurantRequest req) {
        Restaurant c = Restaurant.builder()
                .name(req.name()).location(req.location())
                .representative(req.representative())
                .active(req.active() == null || req.active())
                .maxDevices(req.maxDevices() == null ? 2 : req.maxDevices())
                .build();
        c = restaurantRepository.save(c);
        auditService.record("Restaurant", String.valueOf(c.getId()), "CREATE", null, c.getName());
        return toRestaurant(c);
    }

    @Transactional
    public RestaurantResponse updateRestaurant(Long id, RestaurantRequest req) {
        Restaurant c = restaurantRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Restaurant no encontrado: " + id));
        String before = c.getName() + "|location=" + c.getLocation() + "|maxDevices=" + c.getMaxDevices();
        c.setName(req.name());
        c.setLocation(req.location());
        c.setRepresentative(req.representative());
        if (req.active() != null) c.setActive(req.active());
        if (req.maxDevices() != null) c.setMaxDevices(req.maxDevices());
        c = restaurantRepository.save(c);
        auditService.record("Restaurant", String.valueOf(id), "UPDATE", before, c.getName() + "|location=" + c.getLocation() + "|maxDevices=" + c.getMaxDevices());
        return toRestaurant(c);
    }

    private RestaurantResponse toRestaurant(Restaurant c) {
        long connected = deviceRepository.countByRestaurantIdAndConnectedTrue(c.getId());
        return new RestaurantResponse(c.getId(), c.getName(), c.getLocation(), c.getRepresentative(),
                c.isActive(), c.getMaxDevices(), connected);
    }



    // ----------------- Horarios -----------------
    @Transactional(readOnly = true)
    public List<ScheduleResponse> listSchedules() {
        return scheduleRepository.findAll().stream().map(this::toSchedule).toList();
    }

    @Transactional
    public ScheduleResponse upsertSchedule(ScheduleRequest req) {
        if (!req.endTime().isAfter(req.startTime())) {
            throw new BusinessException("INVALID_RANGE", "La hora fin debe ser posterior a la hora inicio.");
        }
        Schedule s = scheduleRepository.findFirstByOrderByIdAsc().orElseGet(Schedule::new);
        s.setStartTime(req.startTime());
        s.setEndTime(req.endTime());
        s.setActive(req.active() == null || req.active());
        s.setUpdatedAt(java.time.OffsetDateTime.now());
        String before = s.getId() == null ? null : "anterior";
        s = scheduleRepository.save(s);
        auditService.record("Schedule", String.valueOf(s.getId()), "UPSERT", before,
                req.startTime() + "-" + req.endTime());
        return toSchedule(s);
    }

    private ScheduleResponse toSchedule(Schedule s) {
        return new ScheduleResponse(s.getId(), s.getStartTime(), s.getEndTime(), s.isActive());
    }
}
