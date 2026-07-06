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

    boolean existsByPublicCode(String publicCode);

    List<Employee> findByDeletedFalseOrderByFullName();

    @Query("""
            SELECT e FROM Employee e
            WHERE e.deleted = false
              AND (:term IS NULL OR LOWER(e.fullName) LIKE LOWER(CONCAT('%', :term, '%'))
                   OR e.identityCard LIKE CONCAT('%', :term, '%'))
            """)
    Page<Employee> search(@Param("term") String term, Pageable pageable);

    List<Employee> findByDeletedFalseAndStatus(com.eatfood.control.domain.EmployeeStatus status);

    /**
     * Empleados activos que NO consumieron en la fecha dada, resuelto en SQL con un
     * NOT IN sobre consumos (evita cargar todos los empleados y filtrar en memoria).
     */
    @Query("""
            SELECT e FROM Employee e
            WHERE e.deleted = false AND e.status = :status
              AND e.id NOT IN (SELECT c.employee.id FROM Consumption c WHERE c.businessDate = :date)
            ORDER BY e.fullName
            """)
    List<Employee> findActiveNotConsumed(@Param("status") com.eatfood.control.domain.EmployeeStatus status,
                                         @Param("date") java.time.LocalDate date);

    long countByDeletedFalseAndStatus(com.eatfood.control.domain.EmployeeStatus status);

    @Query("""
            SELECT e FROM Employee e
            WHERE e.deleted = false AND e.status = com.eatfood.control.domain.EmployeeStatus.ACTIVE
              AND LOWER(e.fullName) LIKE LOWER(CONCAT('%', :name, '%'))
            ORDER BY e.fullName
            """)
    List<Employee> searchActiveByName(@Param("name") String name);
}
