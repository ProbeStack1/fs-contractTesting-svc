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
@Document(collection = "pact_files")
public class PactFile {

    @Id
    private String id;

    private String consumerName;
    private String consumerVersion;
    private String providerName;
    private String mockServerId;
    private String contractId;

    private Instant generatedAt;

    private String status;          // PENDING_VERIFICATION | VERIFIED | FAILED

    private List<PactInteraction> interactions = new ArrayList<>();
}
