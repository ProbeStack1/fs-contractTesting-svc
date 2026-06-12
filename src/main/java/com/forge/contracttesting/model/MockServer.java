package com.forge.contracttesting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mock_servers")
public class MockServer {

    @Id
    private String id;

    private String contractId;          // link to Contract
    private String microserviceId;      // optional — for future project integration
    private String specMetadataId;      // optional — link to external spec metadata

    private String name;
    private String mockServiceName;
    private String apiSpecName;

    private String mockUrl;             // short slug, e.g. "mock-abc12345"
    private String mockServerUrl;       // full runtime URL consumers call

    private Boolean isPrivate;
    private Integer delayMs;            // server-level default delay
    private Integer responseLatencyMs;  // alias kept for spec-import compatibility
    private Long requestCount;

    private Instant createdAt;
    private Instant updatedAt;
}
