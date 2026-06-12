package com.forge.contracttesting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MockServerRunResult {
    private String mockServerId;
    private String mockServerName;
    private int totalEndpoints;
    private int successCount;
    private int failureCount;
    private List<EndpointExecutionSummary> results;
}
