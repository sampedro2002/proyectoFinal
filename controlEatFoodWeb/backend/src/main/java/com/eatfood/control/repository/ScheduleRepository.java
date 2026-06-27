package com.eatfood.control.repository;

import com.eatfood.control.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    List<Schedule> findByActiveTrue();
    Optional<Schedule> findByMealTypeIdAndActiveTrue(Long mealTypeId);
    Optional<Schedule> findByMealTypeId(Long mealTypeId);
}
