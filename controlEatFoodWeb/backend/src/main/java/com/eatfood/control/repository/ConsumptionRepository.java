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

    boolean existsByEmployeeIdAndBusinessDate(Long employeeId, LocalDate businessDate);

    /** Nº de consumos que el empleado ya retiró en el día (para el tope según permisos). */
    long countByEmployeeIdAndBusinessDate(Long employeeId, LocalDate businessDate);

    Optional<Consumption> findByEmployeeIdAndBusinessDate(Long employeeId, LocalDate businessDate);

    Optional<Consumption> findByClientUuid(UUID clientUuid);

    List<Consumption> findByBusinessDateAndRestaurantId(LocalDate businessDate, Long restaurantId);

    @EntityGraph(attributePaths = {"restaurant", "employee"})
    @Query("""
            SELECT c FROM Consumption c
            WHERE c.businessDate = :date AND c.restaurant.id = :restaurantId
            ORDER BY c.consumedAt DESC
            """)
    List<Consumption> findByBusinessDateAndRestaurantIdWithDetails(
            @Param("date") LocalDate date, @Param("restaurantId") Long restaurantId);

    /**
     * Reporte de consumos. Usa {@link EntityGraph} para resolver en una sola consulta
     * las relaciones LAZY ({@code restaurant}) que
     * {@link com.eatfood.control.service.ReportService#toRow} accede después,
     * evitando un problema de N+1 SELECT.
     */
    @EntityGraph(attributePaths = {"restaurant"})
    @Query("""
            SELECT c FROM Consumption c
            WHERE c.businessDate BETWEEN :from AND :to
              AND (:restaurantId IS NULL OR c.restaurant.id = :restaurantId)
              AND (:employeeId IS NULL OR c.employee.id = :employeeId)
            ORDER BY c.consumedAt DESC
            """)
    List<Consumption> report(@Param("from") LocalDate from,
                             @Param("to") LocalDate to,
                             @Param("restaurantId") Long restaurantId,
                             @Param("employeeId") Long employeeId);

    long countByBusinessDate(LocalDate date);

    long countByBusinessDateAndMealName(LocalDate date, String mealName);

    @Query("SELECT COUNT(DISTINCT c.employee.id) FROM Consumption c WHERE c.businessDate = :date")
    long countDistinctEmployees(@Param("date") LocalDate date);



    @Query("SELECT c.employee.id FROM Consumption c WHERE c.businessDate = :date")
    List<Long> findConsumedEmployeeIds(@Param("date") LocalDate date);

    /**
     * Conteo de consumos agrupado por día en un rango, en una sola consulta.
     * Evita el N+1 de recorrer día por día. Devuelve filas {@code [LocalDate, Long]};
     * los días sin consumos no aparecen y el servicio los rellena con cero.
     */
    @Query("""
            SELECT c.businessDate, COUNT(c) FROM Consumption c
            WHERE c.businessDate BETWEEN :from AND :to
            GROUP BY c.businessDate
            """)
    List<Object[]> countGroupedByBusinessDate(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
