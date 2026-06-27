package com.eatfood.control.repository;

import com.eatfood.control.domain.Consumption;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsumptionRepository extends JpaRepository<Consumption, Long> {

    boolean existsByEmployeeIdAndMealTypeIdAndBusinessDate(Long employeeId, Long mealTypeId, LocalDate businessDate);

    Optional<Consumption> findByEmployeeIdAndMealTypeIdAndBusinessDate(Long employeeId, Long mealTypeId, LocalDate businessDate);

    Optional<Consumption> findByClientUuid(UUID clientUuid);

    List<Consumption> findByBusinessDateAndCateringId(LocalDate businessDate, Long cateringId);

    /**
     * Reporte de consumos. Usa {@link EntityGraph} para resolver en una sola consulta
     * las relaciones LAZY ({@code employee.position}, {@code catering}, {@code mealType})
     * que {@link com.eatfood.control.service.ReportService#toRow} accede después,
     * evitando un problema de N+1 SELECT.
     */
    @EntityGraph(attributePaths = {"employee.position", "catering", "mealType"})
    @Query("""
            SELECT c FROM Consumption c
            WHERE c.businessDate BETWEEN :from AND :to
              AND (:cateringId IS NULL OR c.catering.id = :cateringId)
              AND (:mealTypeId IS NULL OR c.mealType.id = :mealTypeId)
              AND (:employeeId IS NULL OR c.employee.id = :employeeId)
            ORDER BY c.consumedAt DESC
            """)
    List<Consumption> report(@Param("from") LocalDate from,
                             @Param("to") LocalDate to,
                             @Param("cateringId") Long cateringId,
                             @Param("mealTypeId") Long mealTypeId,
                             @Param("employeeId") Long employeeId);

    long countByBusinessDate(LocalDate date);

    @Query("SELECT COUNT(DISTINCT c.employee.id) FROM Consumption c WHERE c.businessDate = :date")
    long countDistinctEmployees(@Param("date") LocalDate date);

    long countByBusinessDateAndMealTypeId(LocalDate date, Long mealTypeId);

    @Query("SELECT c.employee.id FROM Consumption c WHERE c.businessDate = :date")
    List<Long> findConsumedEmployeeIds(@Param("date") LocalDate date);
}
