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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ConsumptionRepository extends JpaRepository<Consumption, Long> {

    boolean existsByEmployeeIdAndBusinessDate(Long employeeId, LocalDate businessDate);

    /** "First" porque un empleado puede tener hasta 2 consumos el mismo día (almuerzo y merienda). */
    Optional<Consumption> findFirstByEmployeeIdAndBusinessDate(Long employeeId, LocalDate businessDate);

    /** Nombres de las comidas ya registradas hoy para el empleado (p. ej. "Almuerzo", "Merienda"). */
    @Query("SELECT c.mealName FROM Consumption c WHERE c.employee.id = :employeeId AND c.businessDate = :date AND c.cancelled = FALSE")
    List<String> findMealNamesByEmployeeIdAndBusinessDate(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);

    Optional<Consumption> findByClientUuid(UUID clientUuid);

    @Query("SELECT c FROM Consumption c WHERE c.businessDate = :businessDate AND c.restaurant.id = :restaurantId AND c.cancelled = FALSE")
    List<Consumption> findByBusinessDateAndRestaurantId(@Param("businessDate") LocalDate businessDate, @Param("restaurantId") Long restaurantId);

    @EntityGraph(attributePaths = {"restaurant", "employee"})
    @Query("""
            SELECT c FROM Consumption c
            WHERE c.businessDate = :date AND c.restaurant.id = :restaurantId AND c.cancelled = FALSE
            ORDER BY c.consumedAt DESC
            """)
    List<Consumption> findByBusinessDateAndRestaurantIdWithDetails(
            @Param("date") LocalDate date, @Param("restaurantId") Long restaurantId);

    /**
     * Reporte de consumos. Usa {@link EntityGraph} para resolver en una sola consulta
     * las relaciones LAZY ({@code restaurant}, {@code employee} y
     * {@code proxyEmployee}) que {@link com.eatfood.control.service.ReportService#toRow}
     * accede después, evitando un problema de N+1 SELECT. El filtro
     * {@code methods} (opcional) acota por metodo de registro
     * (FINGERPRINT/MANUAL/EXTERNAL); vacio o null = todos.
     */
    @EntityGraph(attributePaths = {"restaurant", "employee", "proxyEmployee"})
    @Query("""
            SELECT c FROM Consumption c
            WHERE c.businessDate BETWEEN :from AND :to
              AND (:restaurantId IS NULL OR c.restaurant.id = :restaurantId)
              AND (:employeeId IS NULL OR c.employee.id = :employeeId)
              AND (:methods IS NULL OR c.method IN :methods)
              AND (:showCancelled = TRUE OR c.cancelled = FALSE)
            ORDER BY c.consumedAt DESC
            """)
    List<Consumption> report(@Param("from") LocalDate from,
                             @Param("to") LocalDate to,
                             @Param("restaurantId") Long restaurantId,
                             @Param("employeeId") Long employeeId,
                             @Param("methods") List<com.eatfood.control.domain.Method> methods,
                             @Param("showCancelled") boolean showCancelled);

    long countByBusinessDateAndCancelledFalse(LocalDate date);

    long countByBusinessDateAndMealNameAndCancelledFalse(LocalDate date, String mealName);

    @Query("SELECT COUNT(DISTINCT c.employee.id) FROM Consumption c WHERE c.businessDate = :date AND c.cancelled = FALSE")
    long countDistinctEmployees(@Param("date") LocalDate date);



    @Query("SELECT c.employee.id FROM Consumption c WHERE c.businessDate = :date AND c.cancelled = FALSE")
    List<Long> findConsumedEmployeeIds(@Param("date") LocalDate date);

    /**
     * Conteo de consumos agrupado por día en un rango, en una sola consulta.
     * Evita el N+1 de recorrer día por día. Devuelve filas {@code [LocalDate, Long]};
     * los días sin consumos no aparecen y el servicio los rellena con cero.
     */
    @Query("""
            SELECT c.businessDate, COUNT(c) FROM Consumption c
            WHERE c.businessDate BETWEEN :from AND :to AND c.cancelled = FALSE
            GROUP BY c.businessDate
            """)
    List<Object[]> countGroupedByBusinessDate(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query(value = """
            SELECT DISTINCT c FROM Consumption c
            JOIN FETCH c.employee
            JOIN FETCH c.restaurant
            LEFT JOIN FETCH c.proxyEmployee pe
            WHERE c.method IN (com.eatfood.control.domain.Method.MANUAL, com.eatfood.control.domain.Method.EXTERNAL)
              AND (:search IS NULL OR
                   LOWER(c.employee.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR
                   LOWER(c.employee.identityCard) LIKE LOWER(CONCAT('%', :search, '%')) OR
                   LOWER(pe.fullName) LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:restaurantId IS NULL OR c.restaurant.id = :restaurantId)
              AND (:cancelled IS NULL OR c.cancelled = :cancelled)
            ORDER BY c.consumedAt DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT c) FROM Consumption c
            LEFT JOIN c.proxyEmployee pe
            WHERE c.method IN (com.eatfood.control.domain.Method.MANUAL, com.eatfood.control.domain.Method.EXTERNAL)
              AND (:search IS NULL OR
                   LOWER(c.employee.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR
                   LOWER(c.employee.identityCard) LIKE LOWER(CONCAT('%', :search, '%')) OR
                   LOWER(pe.fullName) LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:restaurantId IS NULL OR c.restaurant.id = :restaurantId)
              AND (:cancelled IS NULL OR c.cancelled = :cancelled)
            """)
    Page<Consumption> findManualConsumptions(@Param("search") String search,
                                              @Param("restaurantId") Long restaurantId,
                                              @Param("cancelled") Boolean cancelled,
                                              Pageable pageable);
}
