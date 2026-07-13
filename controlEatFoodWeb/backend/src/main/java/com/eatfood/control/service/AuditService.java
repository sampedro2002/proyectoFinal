package com.eatfood.control.service;

import com.eatfood.control.domain.AuditLog;
import com.eatfood.control.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void record(String entity, String entityId, String action, String oldValue, String newValue) {
        AuditLog auditEntry = AuditLog.builder()
                .username(currentUsername())
                .entityName(entity)
                .entityId(entityId)
                .action(action)
                .oldValue(truncate(oldValue))
                .newValue(truncate(newValue))
                .ipAddress(clientIp())
                .deviceInfo(userAgent())
                .build();
        auditLogRepository.save(auditEntry);
    }

    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    private String clientIp() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        String remote = req.getRemoteAddr();
        // X-Forwarded-For solo se acepta desde un reverse proxy local (loopback);
        // un cliente directo podría falsificarlo y contaminar el log de auditoría.
        if ("127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote)) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        }
        return remote;
    }

    private String userAgent() {
        HttpServletRequest req = currentRequest();
        return req != null ? req.getHeader("User-Agent") : null;
    }

    private String truncate(String v) {
        if (v == null) return null;
        return v.length() > 4000 ? v.substring(0, 4000) : v;
    }
}
