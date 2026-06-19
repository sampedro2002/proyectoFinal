package com.eatfood.control.service;

import com.eatfood.control.domain.AuditLog;
import com.eatfood.control.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void record(String entity, String entityId, String action, String oldValue, String newValue) {
        AuditLog log = AuditLog.builder()
                .username(currentUsername())
                .entityName(entity)
                .entityId(entityId)
                .action(action)
                .oldValue(truncate(oldValue))
                .newValue(truncate(newValue))
                .ipAddress(clientIp())
                .deviceInfo(userAgent())
                .build();
        auditLogRepository.save(log);
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
        String xff = req.getHeader("X-Forwarded-For");
        return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : req.getRemoteAddr();
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
