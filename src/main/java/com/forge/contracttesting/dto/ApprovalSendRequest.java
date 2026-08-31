package com.forge.contracttesting.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ApprovalSendRequest {

    @NotBlank(message = "mockServerId is required")
    private String mockServerId;

    // Optional — if omitted the service resolves the address from the
    // consumer's pocEmail (falling back to smeEmail) via the contract link.
    @Email(message = "approverEmail must be a valid email address")
    private String approverEmail;

    /**
     * Who is sending this approval request (email / user-id from the gateway header).
     * The controller reads this from the request header; callers do not set it.
     */
    private String sentBy;

    /** ARCHITECT | CONSUMER */
    @NotBlank(message = "type is required")
    @Pattern(regexp = "ARCHITECT|CONSUMER", message = "type must be ARCHITECT or CONSUMER")
    private String type;

    /** Optional context message included in the approval email. */
    private String notes;

    /**
     * Optional contract-test result summary from the UI, shaped
     * { total, passed, failed }. Included in the approval email so the
     * consumer/approver can see test outcomes without opening the app.
     */
    private java.util.Map<String, Object> testSummary;
}
