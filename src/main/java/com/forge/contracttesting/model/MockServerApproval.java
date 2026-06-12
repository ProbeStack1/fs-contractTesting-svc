package com.forge.contracttesting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Tracks the latest approval state per (mockServerId, type) pair.
 * Mutable — updated in-place when a new request is sent or actioned.
 * The full send history lives in MockServerApprovalHistory.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mock_server_approvals")
@CompoundIndex(name = "mock_type_idx", def = "{'mockServerId': 1, 'type': 1}", unique = true)
public class MockServerApproval {

    @Id
    private String id;

    private String mockServerId;
    private String contractId;

    /** ARCHITECT | CONSUMER */
    private String type;

    /** NOT_INITIATED | SENT | IN_PROGRESS | APPROVED | REJECTED */
    private String status;

    private String approverEmail;
    private String sentBy;
    private Instant sentAt;
    private Instant updatedAt;
}
