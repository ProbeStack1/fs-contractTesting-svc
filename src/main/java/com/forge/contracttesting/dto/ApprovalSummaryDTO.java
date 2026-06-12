package com.forge.contracttesting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One row in the "pending / sent approvals" list. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalSummaryDTO {

    private String id;              // history row id
    private String mockServerId;
    private String mockServiceName;
    private String apiSpecName;
    private String type;            // ARCHITECT | CONSUMER
    private String status;          // SENT | IN_PROGRESS | APPROVED | REJECTED
    private String sentBy;
    private Instant sentAt;
    private String approverEmail;
    private String rejectionReason;
}
