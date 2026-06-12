package com.forge.contracttesting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot written each time an approval request is sent.
 * One row per send — the approver sees the state as it was when the email arrived.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mock_server_approval_history")
public class MockServerApprovalHistory {

    @Id
    private String id;

    /** Parent MockServerApproval id */
    private String approvalId;

    private String mockServerId;
    private String contractId;

    /** ARCHITECT | CONSUMER */
    private String type;

    /** SENT | IN_PROGRESS | APPROVED | REJECTED */
    private String status;

    private String approverEmail;
    private String sentBy;
    private Instant sentAt;
    private String rejectionReason;

    // ── Mock server snapshot ──────────────────────────────────────────────────

    private String mockServerUrl;
    private String mockServiceName;
    private String apiSpecName;
    private String notes;

    // ── Contract / spec snapshot ──────────────────────────────────────────────

    private String providerName;
    private String consumerName;
    private String baseUrl;
    private String environment;
    private String specRaw;          // full OpenAPI spec text at send time
    private String authType;         // BEARER | API_KEY | NONE
    private String authValue;

    // ── Consumer / rate-limit snapshot ───────────────────────────────────────

    private String rateLimiting;
    private String apiTps;
    private String quota;
    private String apiKeyInfo;

    // ── Endpoint snapshot (method, path, schema, sample body) ────────────────

    private List<Map<String, Object>> endpoints;
}
