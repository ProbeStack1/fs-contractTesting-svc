package com.forge.contracttesting.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class PactInteraction {

    private String description;
    private String providerState;   // e.g. "user with id 42 exists"

    private String requestMethod;
    private String requestPath;
    private Map<String, String> requestHeaders;
    private Object requestBody;

    private int responseStatus;
    private Map<String, String> responseHeaders;
    private Object responseBody;    // shape only, not exact values
}
