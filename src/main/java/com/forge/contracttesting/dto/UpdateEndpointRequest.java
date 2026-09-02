package com.forge.contracttesting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class UpdateEndpointRequest {
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
    private Boolean requiresAuth;
    private String requiredAuthHeader;
}
