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
        Integer testTotal = extractTestCount(req.getTestSummary(), "total");
        Integer testPassed = extractTestCount(req.getTestSummary(), "passed");
        Integer testFailed = extractTestCount(req.getTestSummary(), "failed");

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
                .testTotal(testTotal)
                .testPassed(testPassed)
                .testFailed(testFailed)
                .build();

        // ── Send email ────────────────────────────────────────────────────────
        // Sent before the final save so the delivery outcome (success or the
        // reason it failed, e.g. a missing/invalid SENDGRID_API_KEY secret or
        // template id) is persisted on the same history row instead of being
        // silently swallowed while the API still reports success.
        EmailService.EmailDeliveryResult emailResult = sendApprovalEmail(history, approverEmail, type);
        history.setEmailSent(emailResult.success());
        history.setEmailError(emailResult.success() ? null : emailResult.errorMessage());

        history = historyRepository.save(history);

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

    private EmailService.EmailDeliveryResult sendApprovalEmail(MockServerApprovalHistory history, String toEmail, String type) {
        String subject = buildSubject(history);
        String html = buildEmailHtml(history);

        EmailService.EmailDeliveryResult result = emailService.sendHtmlEmail(toEmail, subject, html);
        if (!result.success()) {
            log.warn("Approval email delivery failed for history {}: {}", history.getId(), result.errorMessage());
        }
        return result;
    }

    /** Pulls an int out of the UI's { total, passed, failed } testSummary map, if present. */
    private Integer extractTestCount(Map<String, Object> testSummary, String key) {
        if (testSummary == null) return null;
        Object value = testSummary.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ── Email HTML generation ────────────────────────────────────────────────
    //
    // Builds a fully self-contained HTML email in code — no SendGrid dashboard
    // dynamic template required. Every field captured in the history snapshot
    // (mock service, full OpenAPI spec, every endpoint's schema/samples, auth,
    // rate limits, and test results) is rendered directly into the message.

    private String buildSubject(MockServerApprovalHistory h) {
        String kind = "CONSUMER".equalsIgnoreCase(h.getType()) ? "Consumer" : "Architect";
        String service = orEmpty(h.getMockServiceName());
        return "Contract Testing: " + kind + " approval requested"
                + (service.isBlank() ? "" : " — " + service);
    }

    private String buildEmailHtml(MockServerApprovalHistory h) {
        boolean isConsumer = "CONSUMER".equalsIgnoreCase(h.getType());
        String base = approvalBaseUrl + "/approvals/" + h.getId();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><body style=\"margin:0;padding:0;background:#f1f5f9;")
            .append("font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;\">")
            .append("<div style=\"max-width:680px;margin:0 auto;padding:24px 16px;\">");

        // Header banner
        html.append("<div style=\"background:#0f172a;border-radius:12px 12px 0 0;padding:24px;\">")
            .append("<p style=\"margin:0;color:#94a3b8;font-size:12px;font-weight:700;")
            .append("letter-spacing:.05em;text-transform:uppercase;\">Contract Testing</p>")
            .append("<h1 style=\"margin:6px 0 0;color:#f8fafc;font-size:20px;\">")
            .append(isConsumer ? "Consumer approval requested" : "Architect approval requested")
            .append("</h1></div>");

        html.append("<div style=\"background:#ffffff;border:1px solid #e2e8f0;border-top:none;")
            .append("border-radius:0 0 12px 12px;padding:24px;\">");

        html.append("<p style=\"margin:0 0 20px;color:#334155;font-size:14px;line-height:1.6;\">")
            .append(escapeHtml(orEmpty(h.getSentBy())))
            .append(" shared the mock service <strong>")
            .append(escapeHtml(orEmpty(h.getMockServiceName())))
            .append("</strong> for your review and approval.</p>");

        if (h.getNotes() != null && !h.getNotes().isBlank()) {
            html.append(noteBox(h.getNotes()));
        }
        if (h.getTestTotal() != null) {
            html.append(testResultsSection(h));
        }

        html.append(metaSection(h));
        html.append(endpointsSection(h));
        html.append(specSection(h));
        html.append(actionButtons(base, base + "/approve", base + "/reject"));

        html.append("</div>")
            .append("<p style=\"text-align:center;color:#94a3b8;font-size:11px;margin-top:16px;\">")
            .append("Sent automatically by the Contract Testing platform.</p>")
            .append("</div></body></html>");

        return html.toString();
    }

    private String sectionTitle(String title) {
        return "<h2 style=\"margin:24px 0 10px;font-size:12px;font-weight:700;color:#0f172a;"
                + "text-transform:uppercase;letter-spacing:.03em;border-bottom:1px solid #e2e8f0;"
                + "padding-bottom:6px;\">" + escapeHtml(title) + "</h2>";
    }

    private String noteBox(String notes) {
        return "<div style=\"background:#eff6ff;border:1px solid #bfdbfe;border-radius:8px;"
                + "padding:12px 14px;margin-bottom:20px;\">"
                + "<p style=\"margin:0;color:#1e40af;font-size:13px;\"><strong>Note:</strong> "
                + escapeHtml(notes) + "</p></div>";
    }

    private String testResultsSection(MockServerApprovalHistory h) {
        int total = h.getTestTotal() != null ? h.getTestTotal() : 0;
        int passed = h.getTestPassed() != null ? h.getTestPassed() : 0;
        int failed = h.getTestFailed() != null ? h.getTestFailed() : 0;
        String failedColor = failed > 0 ? "#dc2626" : "#94a3b8";

        return sectionTitle("Contract Test Results")
                + "<table role=\"presentation\" style=\"width:100%;border-collapse:separate;"
                + "border-spacing:8px 0;margin:0 0 20px -8px;\"><tr>"
                + statCell("Total", String.valueOf(total), "#334155")
                + statCell("Passed", String.valueOf(passed), "#16a34a")
                + statCell("Failed", String.valueOf(failed), failedColor)
                + "</tr></table>";
    }

    private String statCell(String label, String value, String color) {
        return "<td style=\"width:33%;background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;"
                + "padding:12px;text-align:center;\">"
                + "<div style=\"font-size:20px;font-weight:700;color:" + color + ";\">" + value + "</div>"
                + "<div style=\"font-size:11px;color:#64748b;font-weight:600;margin-top:2px;\">" + label + "</div>"
                + "</td>";
    }

    private String metaSection(MockServerApprovalHistory h) {
        StringBuilder sb = new StringBuilder();
        sb.append(sectionTitle("Service & Contract Details"));
        sb.append("<table role=\"presentation\" style=\"width:100%;border-collapse:collapse;")
          .append("font-size:13px;margin-bottom:20px;\">");
        sb.append(metaRow("Mock service", h.getMockServiceName()));
        sb.append(metaRow("Mock server URL", h.getMockServerUrl()));
        sb.append(metaRow("API spec", h.getApiSpecName()));
        sb.append(metaRow("Provider", h.getProviderName()));
        sb.append(metaRow("Consumer", h.getConsumerName()));
        sb.append(metaRow("Base URL", h.getBaseUrl()));
        sb.append(metaRow("Environment", h.getEnvironment()));
        sb.append(metaRow("Auth type", h.getAuthType()));
        sb.append(metaRow("Auth value", h.getAuthValue()));
        sb.append(metaRow("Rate limiting", h.getRateLimiting()));
        sb.append(metaRow("API TPS", h.getApiTps()));
        sb.append(metaRow("Quota", h.getQuota()));
        sb.append(metaRow("API key info", h.getApiKeyInfo()));
        sb.append("</table>");
        return sb.toString();
    }

    private String metaRow(String label, String value) {
        String v = (value == null || value.isBlank()) ? "—" : escapeHtml(value);
        return "<tr>"
                + "<td style=\"padding:6px 12px 6px 0;color:#64748b;font-weight:600;white-space:nowrap;"
                + "vertical-align:top;width:160px;\">" + escapeHtml(label) + "</td>"
                + "<td style=\"padding:6px 0;color:#0f172a;word-break:break-word;\">" + v + "</td>"
                + "</tr>";
    }

    private String endpointsSection(MockServerApprovalHistory h) {
        List<Map<String, Object>> eps = h.getEndpoints() != null ? h.getEndpoints() : Collections.emptyList();
        StringBuilder sb = new StringBuilder();
        sb.append(sectionTitle("Mock Endpoints (" + eps.size() + ")"));
        if (eps.isEmpty()) {
            sb.append("<p style=\"color:#94a3b8;font-size:13px;margin:0 0 20px;\">")
              .append("No active endpoints on this mock server.</p>");
            return sb.toString();
        }
        for (Map<String, Object> ep : eps) {
            sb.append(endpointCard(ep));
        }
        return sb.toString();
    }

    private String endpointCard(Map<String, Object> ep) {
        String method = str(ep.get("method"));
        String path = str(ep.get("path"));
        String status = str(ep.get("responseStatus"));
        String validationMode = str(ep.get("validationMode"));
        String delay = str(ep.get("delayMs"));
        String requestSample = str(ep.get("requestBodySample"));
        String responseBody = str(ep.get("responseBody"));
        String validationSchema = str(ep.get("validationSchema"));

        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"border:1px solid #e2e8f0;border-radius:8px;padding:12px 14px;")
          .append("margin-bottom:10px;\">");
        sb.append("<div style=\"margin-bottom:6px;\">")
          .append("<span style=\"display:inline-block;background:").append(methodColor(method))
          .append(";color:#fff;font-size:11px;font-weight:700;padding:2px 8px;border-radius:4px;")
          .append("margin-right:8px;\">").append(escapeHtml(method)).append("</span>")
          .append("<span style=\"font-family:monospace;font-size:13px;color:#0f172a;\">")
          .append(escapeHtml(path)).append("</span></div>");
        sb.append("<p style=\"margin:0 0 8px;font-size:12px;color:#64748b;\">Response status: <strong>")
          .append(escapeHtml(status)).append("</strong>")
          .append(validationMode.isBlank() ? "" : " · Validation: <strong>" + escapeHtml(validationMode) + "</strong>")
          .append(delay.isBlank() ? "" : " · Delay: <strong>" + escapeHtml(delay) + "ms</strong>")
          .append("</p>");

        if (!requestSample.isBlank()) sb.append(codeBlock("Request sample", requestSample));
        if (!responseBody.isBlank()) sb.append(codeBlock("Response body", responseBody));
        if (!validationSchema.isBlank()) sb.append(codeBlock("Validation schema", validationSchema));

        sb.append("</div>");
        return sb.toString();
    }

    private String codeBlock(String label, String content) {
        return "<p style=\"margin:8px 0 2px;font-size:11px;color:#64748b;font-weight:600;\">"
                + escapeHtml(label) + "</p>"
                + "<pre style=\"margin:0;background:#0f172a;color:#e2e8f0;padding:10px;border-radius:6px;"
                + "font-size:11px;line-height:1.5;overflow-x:auto;white-space:pre-wrap;word-break:break-word;\">"
                + escapeHtml(content) + "</pre>";
    }

    private String methodColor(String method) {
        return switch (method == null ? "" : method.toUpperCase()) {
            case "GET" -> "#2563eb";
            case "POST" -> "#16a34a";
            case "PUT" -> "#d97706";
            case "PATCH" -> "#7c3aed";
            case "DELETE" -> "#dc2626";
            default -> "#475569";
        };
    }

    private String specSection(MockServerApprovalHistory h) {
        String spec = h.getSpecRaw();
        if (spec == null || spec.isBlank()) {
            return sectionTitle("Full API Specification")
                    + "<p style=\"color:#94a3b8;font-size:13px;margin:0 0 20px;\">No spec available.</p>";
        }
        return sectionTitle("Full API Specification")
                + "<pre style=\"margin:0 0 20px;background:#0f172a;color:#e2e8f0;padding:14px;"
                + "border-radius:8px;font-size:11px;line-height:1.5;overflow-x:auto;white-space:pre-wrap;"
                + "word-break:break-word;max-height:520px;overflow-y:auto;\">" + escapeHtml(spec) + "</pre>";
    }

    private String actionButtons(String reviewUrl, String approveUrl, String rejectUrl) {
        return "<table role=\"presentation\" style=\"margin-top:24px;\"><tr>"
                + actionButton(reviewUrl, "Review Details", "#1e293b")
                + "<td style=\"width:10px;\"></td>"
                + actionButton(approveUrl, "Approve", "#16a34a")
                + "<td style=\"width:10px;\"></td>"
                + actionButton(rejectUrl, "Reject", "#dc2626")
                + "</tr></table>";
    }

    private String actionButton(String url, String label, String color) {
        return "<td><a href=\"" + escapeHtml(url) + "\" style=\"display:inline-block;background:" + color
                + ";color:#ffffff;font-size:13px;font-weight:600;text-decoration:none;padding:10px 18px;"
                + "border-radius:8px;\">" + escapeHtml(label) + "</a></td>";
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
                .emailSent(h.getEmailSent())
                .emailError(h.getEmailError())
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
                .testTotal(h.getTestTotal())
                .testPassed(h.getTestPassed())
                .testFailed(h.getTestFailed())
                .emailSent(h.getEmailSent())
                .emailError(h.getEmailError())
                .build();
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
