package com.eatfood.control.repository;

import com.eatfood.control.domain.Employee;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByIdentityCardAndDeletedFalse(String identityCard);

    /** Búsqueda global (incluye soft-deleted): identity_card es UNIQUE en la BD. */
    Optional<Employee> findByIdentityCard(String identityCard);

    /** ¿Otro empleado (distinto de id) ya usa esta cédula? Para validar en la edición. */
    boolean existsByIdentityCardAndIdNot(String identityCard, Long id);

    /**
     * Igual que {@link #findById}, pero toma un bloqueo pesimista de escritura sobre la fila.
     * Se usa en el escaneo para serializar los escaneos concurrentes del mismo empleado y
     * evitar que dos transacciones lean el mismo conteo de consumos del día antes de que
     * ninguna haga commit (check-then-act), lo que permitiría superar el tope diario.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE e.id = :id")
    Optional<Employee> findByIdForUpdate(@Param("id") Long id);

    boolean existsByIdentityCardAndDeletedFalse(String identityCard);

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
