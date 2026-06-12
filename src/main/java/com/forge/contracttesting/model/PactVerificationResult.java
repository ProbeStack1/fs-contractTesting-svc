package com.forge.contracttesting.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = "pact_verification_results")
public class PactVerificationResult {

    @Id
    private String id;

    private String pactFileId;
    private String contractId;
    private String consumerName;
    private String consumerVersion;
    private String providerName;
    private String providerVersion;
    private String environment;

    private Instant verifiedAt;
    private boolean passed;

    private List<InteractionResult> interactionResults = new ArrayList<>();

    @Data
    @NoArgsConstructor
    public static class InteractionResult {
        private String description;
        private boolean passed;
        private int actualStatus;
        private int expectedStatus;
        private List<String> violations = new ArrayList<>();
    }
}
