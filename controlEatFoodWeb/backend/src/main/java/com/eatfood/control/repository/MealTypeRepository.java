package com.eatfood.control.repository;

import com.eatfood.control.domain.MealType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MealTypeRepository extends JpaRepository<MealType, Long> {
    Optional<MealType> findByCode(String code);
    List<MealType> findByActiveTrueOrderBySortOrder();
}
