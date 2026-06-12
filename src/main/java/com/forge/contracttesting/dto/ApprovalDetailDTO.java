package com.forge.contracttesting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full approval detail shown to the approver.
 * Contains a snapshot of every piece of information that was captured when
 * the approval email was sent: mock server, OpenAPI spec, endpoints with
 * JSON schemas, auth model, error model, and rate-limit policy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalDetailDTO {

    // ── Identity ──────────────────────────────────────────────────────────────

    private String id;              // history row id
    private String mockServerId;
    private String contractId;
    private String type;            // ARCHITECT | CONSUMER
    private String status;
    private String sentBy;
    private Instant sentAt;
    private String approverEmail;
    private String rejectionReason;
    private String notes;

    // ── Mock server ───────────────────────────────────────────────────────────

    private String mockServerUrl;
    private String mockServiceName;
    private String apiSpecName;

    // ── Contract / provider ───────────────────────────────────────────────────

    private String providerName;
    private String consumerName;
    private String baseUrl;
    private String environment;

    // ── OpenAPI spec (full text) ──────────────────────────────────────────────

    private String specRaw;

    // ── Auth model ────────────────────────────────────────────────────────────

    private String authType;        // BEARER | API_KEY | NONE
    private String authValue;       // masked by the service before sending

    // ── Rate-limit policy ─────────────────────────────────────────────────────

    private String rateLimiting;
    private String apiTps;
    private String quota;
    private String apiKeyInfo;

    // ── Endpoint details (method, path, status, schema, sample body) ──────────

    /**
     * Each entry contains:
     *   method, path, responseStatus, responseBody,
     *   validationMode, validationSchema, requestBodySample, delayMs
     */
    private List<Map<String, Object>> endpoints;
}
