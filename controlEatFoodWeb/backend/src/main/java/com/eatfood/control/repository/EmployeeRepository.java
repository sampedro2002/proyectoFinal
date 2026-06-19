package com.eatfood.control.repository;

import com.eatfood.control.domain.Employee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByIdentityCardAndDeletedFalse(String identityCard);

    boolean existsByIdentityCardAndDeletedFalse(String identityCard);

    @Query("""
            SELECT e FROM Employee e
            WHERE e.deleted = false
              AND (:term IS NULL OR LOWER(e.fullName) LIKE LOWER(CONCAT('%', :term, '%'))
                   OR e.identityCard LIKE CONCAT('%', :term, '%'))
            """)
    Page<Employee> search(@Param("term") String term, Pageable pageable);

    List<Employee> findByDeletedFalseAndStatus(com.eatfood.control.domain.EmployeeStatus status);

    long countByDeletedFalseAndStatus(com.eatfood.control.domain.EmployeeStatus status);
}
