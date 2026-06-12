package com.forge.contracttesting.service;

import com.forge.contracttesting.dto.ApprovalDetailDTO;
import com.forge.contracttesting.dto.ApprovalSendRequest;
import com.forge.contracttesting.dto.ApprovalSummaryDTO;
import com.forge.contracttesting.model.*;
import com.forge.contracttesting.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MockApprovalService {

    private final MockServerRepository mockServerRepository;
    private final MockEndpointRepository mockEndpointRepository;
    private final ContractRepository contractRepository;
    private final ConsumerRepository consumerRepository;
    private final MockServerApprovalRepository approvalRepository;
    private final MockServerApprovalHistoryRepository historyRepository;
    private final EmailService emailService;

    @Value("${sendgrid.template.architect-approval}")
    private String architectTemplateId;

    @Value("${sendgrid.template.consumer-approval}")
    private String consumerTemplateId;

    @Value("${approval.base-url}")
    private String approvalBaseUrl;

    // ── Send ─────────────────────────────────────────────────────────────────

    /**
     * Gather all mock-server / contract / consumer data, send the approval
     * email, and persist the approval + history rows.
     */
    public ApprovalSummaryDTO sendApproval(ApprovalSendRequest req) {
        MockServer mockServer = mockServerRepository.findById(req.getMockServerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Mock server not found: " + req.getMockServerId()));

        Contract contract = mockServer.getContractId() != null
                ? contractRepository.findById(mockServer.getContractId()).orElse(null)
                : null;

        List<MockEndpoint> activeEndpoints =
                mockEndpointRepository.findByMockIdAndIsActiveTrue(mockServer.getId());

        Consumer consumer = resolveConsumer(contract);

        // If the UI did not pass an explicit approverEmail, derive it from the
        // consumer record (pocEmail first, smeEmail as fallback).
        String approverEmail = resolveApproverEmail(req.getApproverEmail(), consumer);

        // Build the snapshot that goes into both the email and the history row.
        String maskedAuth = maskAuthValue(contract != null ? contract.getAuthValue() : null);
        List<Map<String, Object>> endpointSnapshots = buildEndpointSnapshots(activeEndpoints);

        // ── Upsert parent approval row ────────────────────────────────────────
        String type = req.getType().toUpperCase();
        MockServerApproval approval = approvalRepository
                .findByMockServerIdAndType(mockServer.getId(), type)
                .orElseGet(() -> MockServerApproval.builder()
                        .mockServerId(mockServer.getId())
                        .contractId(mockServer.getContractId())
                        .type(type)
                        .build());

        approval.setStatus("SENT");
        approval.setApproverEmail(approverEmail);
        approval.setSentBy(req.getSentBy());
        approval.setSentAt(Instant.now());
        approval.setUpdatedAt(Instant.now());
        approval = approvalRepository.save(approval);

        // ── Append immutable history row ──────────────────────────────────────
        MockServerApprovalHistory history = MockServerApprovalHistory.builder()
                .approvalId(approval.getId())
                .mockServerId(mockServer.getId())
                .contractId(mockServer.getContractId())
                .type(type)
                .status("SENT")
                .approverEmail(approverEmail)
                .sentBy(req.getSentBy())
                .sentAt(Instant.now())
                .notes(req.getNotes())
                .mockServerUrl(mockServer.getMockServerUrl())
                .mockServiceName(mockServer.getMockServiceName())
                .apiSpecName(mockServer.getApiSpecName())
                .providerName(contract != null ? contract.getProviderName() : null)
                .consumerName(contract != null ? contract.getConsumerName() : null)
                .baseUrl(contract != null ? contract.getBaseUrl() : null)
                .environment(contract != null ? contract.getEnvironment() : null)
                .specRaw(contract != null ? contract.getSpecRaw() : null)
                .authType(contract != null ? contract.getAuthType() : null)
                .authValue(maskedAuth)
                .rateLimiting(consumer != null ? consumer.getRateLimiting() : null)
                .apiTps(consumer != null ? consumer.getApiTps() : null)
                .quota(consumer != null ? consumer.getQuota() : null)
                .apiKeyInfo(consumer != null ? consumer.getApiKeyInfo() : null)
                .endpoints(endpointSnapshots)
                .build();

        history = historyRepository.save(history);

        // ── Send email ────────────────────────────────────────────────────────
        sendApprovalEmail(history, approverEmail, type);

        return toSummary(history);
    }

    // ── Approver views ────────────────────────────────────────────────────────

    /** All history rows where this user is the approver, newest first. */
    public List<ApprovalSummaryDTO> getPendingForApprover(String approverEmail) {
        return historyRepository
                .findByApproverEmailIgnoreCaseOrderBySentAtDesc(approverEmail)
                .stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    /**
     * Full detail view for the approver.
     * Auto-promotes status SENT → IN_PROGRESS on first open.
     */
    public ApprovalDetailDTO getDetailForApprover(String historyId, String approverEmail) {
        MockServerApprovalHistory history = findHistory(historyId);
        assertApprover(history, approverEmail);
        promoteToInProgress(history);
        return toDetail(history);
    }

    // ── Sender views ──────────────────────────────────────────────────────────

    /** All history rows sent by this user, newest first. */
    public List<ApprovalSummaryDTO> getSentByUser(String sentBy) {
        return historyRepository.findBySentByOrderBySentAtDesc(sentBy)
                .stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    /** Full detail — accessible by either the sender or the approver. */
    public ApprovalDetailDTO getDetailForUser(String historyId, String userEmail) {
        MockServerApprovalHistory history = findHistory(historyId);

        boolean isSender = userEmail.equalsIgnoreCase(history.getSentBy());
        boolean isApprover = userEmail.equalsIgnoreCase(history.getApproverEmail());
        if (!isSender && !isApprover) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to view this approval");
        }

        if (isApprover) {
            promoteToInProgress(history);
        }

        return toDetail(history);
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    public void approve(String historyId, String approverEmail) {
        updateStatus(historyId, approverEmail, "APPROVED", null);
    }

    public void reject(String historyId, String approverEmail, String reason) {
        updateStatus(historyId, approverEmail, "REJECTED", reason);
    }

    private void updateStatus(String historyId, String approverEmail, String newStatus, String reason) {
        MockServerApprovalHistory history = findHistory(historyId);
        assertApprover(history, approverEmail);

        if ("APPROVED".equals(history.getStatus()) || "REJECTED".equals(history.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Request already " + history.getStatus().toLowerCase());
        }

        history.setStatus(newStatus);
        history.setRejectionReason(reason);
        historyRepository.save(history);

        // Cascade to parent only if this row is the latest for (mockServerId, type).
        cascadeToParent(history, newStatus);
    }

    // ── History list for a mock server ────────────────────────────────────────

    public List<ApprovalSummaryDTO> getHistoryForMock(String mockServerId, int page, int size) {
        return historyRepository
                .findByMockServerIdOrderBySentAtDesc(mockServerId, PageRequest.of(page, size))
                .getContent()
                .stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendApprovalEmail(MockServerApprovalHistory history, String toEmail, String type) {
        Map<String, Object> data = buildEmailPayload(history);
        String templateId = "ARCHITECT".equals(type) ? architectTemplateId : consumerTemplateId;

        EmailService.EmailDeliveryResult result = emailService.sendDynamicEmail(toEmail, data, templateId);
        if (!result.success()) {
            log.warn("Approval email delivery failed for history {}: {}", history.getId(), result.errorMessage());
        }
    }

    /**
     * Builds the complete template-data map sent to SendGrid.
     *
     * Variables available in the template:
     *   approverEmail, sentBy, type, notes,
     *   mockServiceName, mockServerUrl, apiSpecName,
     *   providerName, consumerName, baseUrl, environment,
     *   specRaw, authType, authValue,
     *   rateLimiting, apiTps, quota, apiKeyInfo,
     *   endpointCount, endpoints (array),
     *   reviewUrl, approveUrl, rejectUrl
     */
    private Map<String, Object> buildEmailPayload(MockServerApprovalHistory h) {
        Map<String, Object> data = new LinkedHashMap<>();

        // Identity
        data.put("approverEmail", h.getApproverEmail());
        data.put("sentBy", h.getSentBy());
        data.put("type", h.getType());
        data.put("notes", h.getNotes() != null ? h.getNotes() : "");

        // Mock server
        data.put("mockServiceName", orEmpty(h.getMockServiceName()));
        data.put("mockServerUrl", orEmpty(h.getMockServerUrl()));
        data.put("apiSpecName", orEmpty(h.getApiSpecName()));

        // Contract / provider info
        data.put("providerName", orEmpty(h.getProviderName()));
        data.put("consumerName", orEmpty(h.getConsumerName()));
        data.put("baseUrl", orEmpty(h.getBaseUrl()));
        data.put("environment", orEmpty(h.getEnvironment()));

        // OpenAPI spec — full text so approver can inspect all schemas, error models, paths
        data.put("specRaw", h.getSpecRaw() != null ? h.getSpecRaw() : "No spec available");

        // Auth model
        data.put("authType", orEmpty(h.getAuthType()));
        data.put("authValue", orEmpty(h.getAuthValue()));   // already masked

        // Rate-limit policy
        data.put("rateLimiting", orEmpty(h.getRateLimiting()));
        data.put("apiTps", orEmpty(h.getApiTps()));
        data.put("quota", orEmpty(h.getQuota()));
        data.put("apiKeyInfo", orEmpty(h.getApiKeyInfo()));

        // Endpoints (method, path, status, response, validation schema, sample body)
        List<Map<String, Object>> eps = h.getEndpoints() != null ? h.getEndpoints() : Collections.emptyList();
        data.put("endpoints", eps);
        data.put("endpointCount", eps.size());

        // Action links for email buttons
        String base = approvalBaseUrl + "/approvals/" + h.getId();
        data.put("reviewUrl", base);
        data.put("approveUrl", base + "/approve");
        data.put("rejectUrl", base + "/reject");

        return data;
    }

    private List<Map<String, Object>> buildEndpointSnapshots(List<MockEndpoint> endpoints) {
        if (endpoints == null) return Collections.emptyList();
        return endpoints.stream().map(ep -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", ep.getMethod());
            m.put("path", ep.getPath());
            m.put("responseStatus", ep.getResponseStatus());
            m.put("responseBody", ep.getResponseBody());
            m.put("responseHeaders", ep.getResponseHeaders());
            m.put("validationMode", ep.getValidationMode());
            m.put("validationSchema", ep.getValidationSchema());   // JSON Schema string
            m.put("requestBodySample", ep.getRequestBodySample());
            m.put("delayMs", ep.getDelayMs());
            return m;
        }).collect(Collectors.toList());
    }

    private Consumer resolveConsumer(Contract contract) {
        if (contract == null || contract.getConsumerName() == null) return null;
        List<Consumer> consumers = consumerRepository.findByConsumerName(contract.getConsumerName());
        return consumers.isEmpty() ? null : consumers.get(0);
    }

    /**
     * Determine the final approver email address.
     * Priority order:
     *   1. Explicit value from the UI request (approverEmail field)
     *   2. consumer.pocEmail  — Point of Contact
     *   3. consumer.smeEmail  — Subject Matter Expert
     * Throws 400 if none of the above resolves to a non-blank address.
     */
    private String resolveApproverEmail(String requestEmail, Consumer consumer) {
        if (requestEmail != null && !requestEmail.isBlank()) {
            return requestEmail.trim();
        }
        if (consumer != null) {
            if (consumer.getPocEmail() != null && !consumer.getPocEmail().isBlank()) {
                log.debug("Approver email resolved from consumer pocEmail: {}", consumer.getPocEmail());
                return consumer.getPocEmail().trim();
            }
            if (consumer.getSmeEmail() != null && !consumer.getSmeEmail().isBlank()) {
                log.debug("Approver email resolved from consumer smeEmail: {}", consumer.getSmeEmail());
                return consumer.getSmeEmail().trim();
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cannot determine approver email: no approverEmail in request and no pocEmail/smeEmail " +
                "found on the associated consumer record.");
    }

    /** Mask everything after the first 6 chars to avoid leaking secrets in email. */
    private String maskAuthValue(String value) {
        if (value == null || value.length() <= 6) return "***";
        return value.substring(0, 6) + "***";
    }

    private void promoteToInProgress(MockServerApprovalHistory history) {
        if ("SENT".equals(history.getStatus())) {
            history.setStatus("IN_PROGRESS");
            historyRepository.save(history);

            // Mirror on parent
            approvalRepository.findByMockServerIdAndType(history.getMockServerId(), history.getType())
                    .filter(a -> "SENT".equals(a.getStatus()))
                    .ifPresent(a -> {
                        a.setStatus("IN_PROGRESS");
                        a.setUpdatedAt(Instant.now());
                        approvalRepository.save(a);
                    });
        }
    }

    private void cascadeToParent(MockServerApprovalHistory history, String newStatus) {
        // Only cascade if this history row is still the latest for its (mockServerId, type).
        List<MockServerApprovalHistory> page = historyRepository
                .findByMockServerIdOrderBySentAtDesc(history.getMockServerId(), PageRequest.of(0, 50))
                .getContent()
                .stream()
                .filter(h -> history.getType().equalsIgnoreCase(h.getType()))
                .collect(Collectors.toList());

        if (!page.isEmpty() && page.get(0).getId().equals(history.getId())) {
            approvalRepository.findByMockServerIdAndType(history.getMockServerId(), history.getType())
                    .ifPresent(a -> {
                        a.setStatus(newStatus);
                        a.setUpdatedAt(Instant.now());
                        approvalRepository.save(a);
                    });
        }
    }

    private MockServerApprovalHistory findHistory(String historyId) {
        return historyRepository.findById(historyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Approval request not found: " + historyId));
    }

    private void assertApprover(MockServerApprovalHistory history, String email) {
        if (!email.equalsIgnoreCase(history.getApproverEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not the designated approver for this request");
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private ApprovalSummaryDTO toSummary(MockServerApprovalHistory h) {
        return ApprovalSummaryDTO.builder()
                .id(h.getId())
                .mockServerId(h.getMockServerId())
                .mockServiceName(h.getMockServiceName())
                .apiSpecName(h.getApiSpecName())
                .type(h.getType())
                .status(h.getStatus())
                .sentBy(h.getSentBy())
                .sentAt(h.getSentAt())
                .approverEmail(h.getApproverEmail())
                .rejectionReason(h.getRejectionReason())
                .build();
    }

    private ApprovalDetailDTO toDetail(MockServerApprovalHistory h) {
        return ApprovalDetailDTO.builder()
                .id(h.getId())
                .mockServerId(h.getMockServerId())
                .contractId(h.getContractId())
                .type(h.getType())
                .status(h.getStatus())
                .sentBy(h.getSentBy())
                .sentAt(h.getSentAt())
                .approverEmail(h.getApproverEmail())
                .rejectionReason(h.getRejectionReason())
                .notes(h.getNotes())
                .mockServerUrl(h.getMockServerUrl())
                .mockServiceName(h.getMockServiceName())
                .apiSpecName(h.getApiSpecName())
                .providerName(h.getProviderName())
                .consumerName(h.getConsumerName())
                .baseUrl(h.getBaseUrl())
                .environment(h.getEnvironment())
                .specRaw(h.getSpecRaw())
                .authType(h.getAuthType())
                .authValue(h.getAuthValue())    // already masked at send time
                .rateLimiting(h.getRateLimiting())
                .apiTps(h.getApiTps())
                .quota(h.getQuota())
                .apiKeyInfo(h.getApiKeyInfo())
                .endpoints(h.getEndpoints())
                .build();
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
