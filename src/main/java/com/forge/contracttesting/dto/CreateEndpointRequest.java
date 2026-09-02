package com.forge.contracttesting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class CreateEndpointRequest {
    private String path;
    private String method;
    private Integer responseStatus;
    private Map<String, String> responseHeaders;
    private String responseBody;
    private Integer delayMs;
    private String requestBodySample;
    private String validationMode;      // NONE | EXACT_MATCH | JSON_SCHEMA
    private String validationSchema;
    private Boolean validateMethod;
    private Boolean requiresAuth;
    private String requiredAuthHeader;
}
