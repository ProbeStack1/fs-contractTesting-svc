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
public class EndpointResponse {
    private String id;
    private String mockId;
    private String path;
    private String method;
    private Integer responseStatus;
    private Map<String, String> responseHeaders;
    private String responseBody;
    private Integer delayMs;
    private Boolean isActive;
    private String requestBodySample;
    private String validationMode;
    private String validationSchema;
    private Boolean validateMethod;
    private Instant createdAt;
    private Instant updatedAt;
}
