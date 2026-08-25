package com.forge.contracttesting.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.contracttesting.model.AuditLog;
import com.forge.contracttesting.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Writes into the platform-wide {@code audit_logs} collection (shared by
 * every ForgeStudio service with this writer). Read/search is exposed only
 * by fs-project-svc's aggregating {@code AuditLogController}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {
    private static final String SERVICE_NAME = "contract-testing";
    private static final String AUDIT_TYPE_REQUEST = "REQUEST";
    private static final String AUDIT_TYPE_ENTITY = "ENTITY";
    private static final int MAX_RESPONSE_BODY_LENGTH = 4000;
    private static final int MAX_TEXT_LENGTH = 4000;
    private static final Set<String> SENSITIVE_KEY_FRAGMENTS = Set.of(
            "password", "token", "secret", "credential", "connectionstring",
            "authorization", "authheader", "apikey", "accesskey"
    );

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public void logHttpRequest(HttpServletRequest request, int statusCode, long durationMs, Throwable error) {
        String path = request.getRequestURI();
        String status = statusCode >= 400 || error != null ? "ERROR" : "SUCCESS";
        String responseBody = trim((String) request.getAttribute("audit.responseBody"), MAX_RESPONSE_BODY_LENGTH);
        save(AuditLog.builder()
                .auditType(AUDIT_TYPE_REQUEST).service(SERVICE_NAME).entityType("HTTP_REQUEST").entityId(path)
                .operation("HTTP_" + request.getMethod()).status(status)
                .message(request.getMethod() + " " + path + " completed with status " + statusCode)
                .performedBy(actor(request))
                .onboardingId(first(request.getHeader("x-onboarding-id"), request.getParameter("onboardingId")))
                .microserviceId(first(request.getHeader("x-microservice-id"), request.getParameter("microserviceId")))
                .requestPayload(requestPayload(request))
                .responsePayload(responsePayload(statusCode, durationMs, responseBody, status))
                .errorMessage(error == null ? failureReason(statusCode, responseBody) : trim(error.getMessage(), MAX_RESPONSE_BODY_LENGTH))
                .createdAt(Instant.now()).build());
    }

    public void logEntityChange(EntityAuditEvent event) {
        if (event == null) {
            return;
        }
        Map<String, Object> beforeSnapshot = snapshot(event.getBeforeSnapshot());
        Map<String, Object> afterSnapshot = snapshot(event.getAfterSnapshot());
        List<Map<String, Object>> changes = changes(beforeSnapshot, afterSnapshot);

        save(AuditLog.builder()
                .auditType(AUDIT_TYPE_ENTITY)
                .service(SERVICE_NAME)
                .entityType(event.getEntityType())
                .entityId(event.getEntityId())
                .operation(first(event.getOperation(), "CHANGE"))
                .status(first(event.getStatus(), "SUCCESS"))
                .message(trim(first(event.getMessage(), entityMessage(event)), MAX_TEXT_LENGTH))
                .performedBy(first(event.getPerformedBy(), "system"))
                .beforeSnapshot(beforeSnapshot)
                .afterSnapshot(afterSnapshot)
                .changes(changes.isEmpty() ? null : changes)
                .metadata(sanitizeMap(event.getMetadata()))
                .createdAt(Instant.now())
                .build());
    }

    private void save(AuditLog auditLog) {
        try {
            auditLogRepository.save(auditLog);
        } catch (Exception ex) {
            log.warn("Unable to persist audit log for {} {}: {}", auditLog.getOperation(), auditLog.getEntityId(), ex.getMessage());
        }
    }

    private Map<String, Object> requestPayload(HttpServletRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", request.getRequestURI());
        payload.put("query", request.getQueryString());
        payload.put("method", request.getMethod());
        payload.put("remoteAddr", request.getRemoteAddr());
        payload.put("userAgent", request.getHeader("user-agent"));
        return payload;
    }

    private Map<String, Object> responsePayload(int statusCode, long durationMs, String responseBody, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("statusCode", statusCode);
        payload.put("durationMs", durationMs);
        if ("ERROR".equals(status) && responseBody != null && !responseBody.isBlank()) payload.put("responseBody", responseBody);
        return payload;
    }

    private String failureReason(int statusCode, String responseBody) {
        if (statusCode < 400) return null;
        return responseBody == null || responseBody.isBlank() ? "HTTP request failed with status " + statusCode : responseBody;
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    private String actor(HttpServletRequest request) {
        return first(request.getHeader("x-user-email"), request.getHeader("x-user-id"),
                request.getParameter("performedBy"), request.getParameter("createdBy"), "system");
    }

    private String first(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private String entityMessage(EntityAuditEvent event) {
        return first(event.getOperation(), "CHANGE") + " " + first(event.getEntityType(), "ENTITY") + " " + first(event.getEntityId(), "");
    }

    private Map<String, Object> snapshot(Object value) {
        if (value == null) {
            return null;
        }
        try {
            Map<String, Object> raw = objectMapper.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
            return sanitizeMap(raw);
        } catch (IllegalArgumentException ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("value", trim(String.valueOf(value), MAX_TEXT_LENGTH));
            return fallback;
        }
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        raw.forEach((key, mapValue) -> sanitized.put(key, sanitizeValue(key, mapValue)));
        return sanitized;
    }

    private Object sanitizeValue(String key, Object value) {
        if (isSensitiveKey(key)) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> nested = new LinkedHashMap<>();
            mapValue.forEach((nestedKey, nestedValue) -> nested.put(String.valueOf(nestedKey), sanitizeValue(String.valueOf(nestedKey), nestedValue)));
            return nested;
        }
        if (value instanceof Collection<?> collectionValue) {
            List<Object> nested = new ArrayList<>();
            for (Object item : collectionValue) {
                nested.add(sanitizeValue(null, item));
            }
            return nested;
        }
        if (value instanceof String text) {
            return trim(text, MAX_TEXT_LENGTH);
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replace("_", "").replace("-", "").toLowerCase();
        return SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    private List<Map<String, Object>> changes(Map<String, Object> before, Map<String, Object> after) {
        List<Map<String, Object>> changes = new ArrayList<>();
        if (before == null || after == null) {
            return changes;
        }
        Set<String> keys = new HashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            Object oldValue = before.get(key);
            Object newValue = after.get(key);
            if (!Objects.equals(oldValue, newValue)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("field", key);
                change.put("oldValue", oldValue);
                change.put("newValue", newValue);
                changes.add(change);
            }
        }
        return changes;
    }

    @Data
    @Builder
    public static class EntityAuditEvent {
        private String entityType;
        private String entityId;
        private String operation;
        private String status;
        private String message;
        private String performedBy;
        private Object beforeSnapshot;
        private Object afterSnapshot;
        private Map<String, Object> metadata;
    }
}
