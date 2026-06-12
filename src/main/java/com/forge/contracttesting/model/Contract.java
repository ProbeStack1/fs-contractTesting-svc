package com.forge.contracttesting.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@Document(collection = "contracts")
public class Contract {

    @Id
    private String id;

    private String name;
    private String type;          // openapi | pact | graphql | grpc | async | security
    private String description;

    // Uploaded spec (raw content stored as string)
    private String specRaw;
    private String specFilename;

    // Parsed spec metadata
    private String specVersion;
    private int endpointCount;
    private int schemaCount;

    // Target service info (Gap 1)
    private String baseUrl;              // e.g. https://api.staging.example.com
    private String providerName;         // e.g. user-service
    private String consumerName;         // e.g. order-service
    private String environment;          // dev / staging / prod
    private String mockServerId;         // link to mock server
    private String pactBrokerId;         // external pact broker reference
    private ContractStatus status = ContractStatus.DRAFT;
    private List<String> verifiedConsumers = new ArrayList<>();

    // Auth for live HTTP validation
    private String authType;             // BEARER | API_KEY | NONE
    private String authValue;            // token or key value

    // Validator rules (enterprise standards)
    private List<Map<String, Object>> validatorRules = new ArrayList<>();

    // Generated test cases
    private List<ContractTest> tests = new ArrayList<>();

    private String orgId;         // multi-tenant support
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
