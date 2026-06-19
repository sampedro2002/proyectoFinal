package com.eatfood.control.web;

import com.eatfood.control.domain.AuditLog;
import com.eatfood.control.repository.AuditLogRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auditoría")
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public Page<AuditLog> list(@RequestParam(required = false, defaultValue = "") String entity,
                               Pageable pageable) {
        return auditLogRepository.findByEntityNameContainingIgnoreCaseOrderByCreatedAtDesc(entity, pageable);
    }
}
