package com.eatfood.control.repository;

import com.eatfood.control.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findByEntityNameContainingIgnoreCaseOrderByCreatedAtDesc(String entityName, Pageable pageable);
}
