package com.eatfood.control.repository;

import com.eatfood.control.domain.FailedScan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface FailedScanRepository extends JpaRepository<FailedScan, Long> {
    @Query("SELECT f FROM FailedScan f WHERE f.occurredAt BETWEEN :from AND :to ORDER BY f.occurredAt DESC")
    List<FailedScan> between(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
