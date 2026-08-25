package com.forge.contracttesting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Platform-wide audit record — shared {@code audit_logs} collection, read by fs-project-svc's aggregator. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "audit_logs")
public class AuditLog {
    @Id private String id;
    @Indexed private String auditType;
    @Indexed private String service;
    @Indexed private String entityType;
    @Indexed private String entityId;
    @Indexed private String operation;
    @Indexed private String status;
    private String message;
    private String performedBy;
    @Indexed private String onboardingId;
    @Indexed private String microserviceId;
    private Map<String, Object> requestPayload;
    private Map<String, Object> responsePayload;
    private Map<String, Object> beforeSnapshot;
    private Map<String, Object> afterSnapshot;
    private List<Map<String, Object>> changes;
    private String errorMessage;
    private Map<String, Object> metadata;
    @Indexed private Instant createdAt;
}
