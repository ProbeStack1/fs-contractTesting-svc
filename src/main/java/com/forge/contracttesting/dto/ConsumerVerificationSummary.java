package com.forge.contracttesting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ConsumerVerificationSummary {
    private String consumerName;
    private String consumerVersion;
    private String pactFileId;
    private boolean verified;
    private String verificationResult;  // PASSED | FAILED | NOT_RUN
    private String blockedReason;
}
