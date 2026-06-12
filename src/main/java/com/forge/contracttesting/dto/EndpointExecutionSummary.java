package com.forge.contracttesting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointExecutionSummary {
    private String endpointId;
    private String method;
    private String path;
    private int statusCode;
    private String statusText;
    private long responseTimeMs;
    private boolean success;
    private String requestBody;
    private String responseBody;
    private String errorMessage;
}
