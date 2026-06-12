package com.forge.contracttesting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MockRequestLogResponse {
    private String id;
    private Instant timestamp;
    private String method;
    private String path;
    private Integer responseStatus;
    private Long responseTimeMs;
    private String requestBody;
    private String responseBody;
    private Map<String, String> requestHeaders;
    private String consumerName;
    private String consumerVersion;
    private String sessionId;
}
