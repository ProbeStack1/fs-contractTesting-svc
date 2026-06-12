package com.forge.contracttesting.controller;

import com.forge.contracttesting.dto.ApprovalDetailDTO;
import com.forge.contracttesting.dto.ApprovalSendRequest;
import com.forge.contracttesting.dto.ApprovalSummaryDTO;
import com.forge.contracttesting.service.MockApprovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for the mock-server approval workflow.
 *
 * Send:
 *   POST /api/v1/approvals/send
 *
 * Approver views:
 *   GET  /api/v1/approvals/pending?approverEmail=
 *   GET  /api/v1/approvals/{historyId}?userEmail=
 *   POST /api/v1/approvals/{historyId}/approve?approverEmail=
 *   POST /api/v1/approvals/{historyId}/reject?approverEmail=&reason=
 *
 * Sender views:
 *   GET  /api/v1/approvals/sent?sentBy=
 *
 * Mock-server history:
 *   GET  /api/v1/approvals/mock/{mockServerId}/history?page=0&size=20
 */
@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class MockApprovalController {

    private final MockApprovalService approvalService;

    // ── Send ─────────────────────────────────────────────────────────────────

    /**
     * Gather mock-server + OpenAPI spec + endpoint schemas + auth + rate-limits,
     * send the approval email, and persist the approval record.
     *
     * Header x-user-email is used as the sender identity when provided.
     */
    @PostMapping("/send")
    public ResponseEntity<ApprovalSummaryDTO> send(
            @Valid @RequestBody ApprovalSendRequest request,
            @RequestHeader(value = "x-user-email", required = false) String userEmail) {
        if (request.getSentBy() == null || request.getSentBy().isBlank()) {
            request.setSentBy(userEmail != null ? userEmail : "system");
        }
        ApprovalSummaryDTO result = approvalService.sendApproval(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    // ── Approver views ────────────────────────────────────────────────────────

    /** All approval requests where this user is the designated approver. */
    @GetMapping("/pending")
    public ResponseEntity<List<ApprovalSummaryDTO>> getPending(
            @RequestParam String approverEmail) {
        return ResponseEntity.ok(approvalService.getPendingForApprover(approverEmail));
    }

    /**
     * Full detail view — usable by both sender and approver.
     * Automatically promotes status from SENT to IN_PROGRESS when the approver opens it.
     */
    @GetMapping("/{historyId}")
    public ResponseEntity<ApprovalDetailDTO> getDetail(
            @PathVariable String historyId,
            @RequestParam String userEmail) {
        return ResponseEntity.ok(approvalService.getDetailForUser(historyId, userEmail));
    }

    /** Approver marks the request as approved. */
    @PostMapping("/{historyId}/approve")
    public ResponseEntity<Map<String, String>> approve(
            @PathVariable String historyId,
            @RequestParam String approverEmail) {
        approvalService.approve(historyId, approverEmail);
        return ResponseEntity.ok(Map.of("status", "APPROVED", "historyId", historyId));
    }

    /** Approver rejects the request with an optional reason. */
    @PostMapping("/{historyId}/reject")
    public ResponseEntity<Map<String, String>> reject(
            @PathVariable String historyId,
            @RequestParam String approverEmail,
            @RequestParam(required = false, defaultValue = "") String reason) {
        approvalService.reject(historyId, approverEmail, reason.isBlank() ? null : reason);
        return ResponseEntity.ok(Map.of("status", "REJECTED", "historyId", historyId));
    }

    // ── Sender views ──────────────────────────────────────────────────────────

    /** All requests sent by this user, newest first. */
    @GetMapping("/sent")
    public ResponseEntity<List<ApprovalSummaryDTO>> getSent(
            @RequestParam String sentBy) {
        return ResponseEntity.ok(approvalService.getSentByUser(sentBy));
    }

    // ── Mock-server history ───────────────────────────────────────────────────

    /** Paged send history for a specific mock server. */
    @GetMapping("/mock/{mockServerId}/history")
    public ResponseEntity<List<ApprovalSummaryDTO>> getMockHistory(
            @PathVariable String mockServerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(approvalService.getHistoryForMock(mockServerId, page, size));
    }
}
