package com.forge.contracttesting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mock_request_logs")
public class MockRequestLog {

    @Id
    private String id;

    private String mockId;              // parent MockServer id
    private String mockUrl;             // slug, e.g. "mock-abc12345"
    private String contractId;

    private String method;
    private String path;
    private Map<String, String> requestHeaders;
    private String requestBody;

    private Integer responseStatus;
    private String responseBody;
    private Map<String, String> responseHeaders;
    private Long responseTimeMs;

    // Consumer identity (Gap 3) — from X-Consumer-Name / X-Consumer-Version headers
    private String consumerName;
    private String consumerVersion;
    private String sessionId;

    private Instant timestamp;
}
