package com.forge.contracttesting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MockServerResponse {
    private String id;
    private String contractId;
    private String microserviceId;
    private String specMetadataId;
    private String apiSpecName;
    private String name;
    private String mockServiceName;
    private String mockUrl;
    private String mockServerUrl;
    private Boolean isPrivate;
    private Integer delayMs;
    private Integer responseLatencyMs;
    private Long requestCount;
    private Instant createdAt;
    private Instant updatedAt;
}
