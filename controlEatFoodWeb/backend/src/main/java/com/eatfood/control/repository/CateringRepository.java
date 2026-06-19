package com.eatfood.control.repository;

import com.eatfood.control.domain.Catering;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CateringRepository extends JpaRepository<Catering, Long> {
    List<Catering> findByActiveTrue();
}
