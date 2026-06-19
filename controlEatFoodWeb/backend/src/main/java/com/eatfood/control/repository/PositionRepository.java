package com.eatfood.control.repository;

import com.eatfood.control.domain.Position;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<Position, Long> {
    boolean existsByNameIgnoreCase(String name);
}
