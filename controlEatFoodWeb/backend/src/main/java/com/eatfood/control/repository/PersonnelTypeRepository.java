package com.eatfood.control.repository;

import com.eatfood.control.domain.PersonnelTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PersonnelTypeRepository extends JpaRepository<PersonnelTypeEntity, Long> {
    Optional<PersonnelTypeEntity> findByCode(String code);
    boolean existsByCode(String code);
    List<PersonnelTypeEntity> findAllByOrderBySortOrderAscNameAsc();
    List<PersonnelTypeEntity> findByActiveTrueOrderBySortOrderAscNameAsc();
}
