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
@Document(collection = "mock_endpoints")
public class MockEndpoint {

    @Id
    private String id;

    private String mockId;              // parent MockServer id

    private String path;                // e.g. /users/{id}
    private String method;              // GET | POST | PUT | PATCH | DELETE

    private Integer responseStatus;     // e.g. 200
    private Map<String, String> responseHeaders;
    private String responseBody;        // raw JSON string

    private Integer delayMs;            // endpoint-level delay overrides server delay
    private Boolean isActive;

    // Request validation
    private String requestBodySample;   // sample body for EXACT_MATCH
    private String validationMode;      // NONE | EXACT_MATCH | JSON_SCHEMA
    private String validationSchema;    // JSON Schema string (for JSON_SCHEMA mode)
    private Boolean validateMethod;     // enforce method matching

    // Auth enforcement — opt-in (null/false preserves every existing
    // endpoint's current unauthenticated behavior). When true, a request
    // missing requiredAuthHeader (default "Authorization") is rejected with
    // 401 before validation/response logic runs - see MockRuntimeService.
    private Boolean requiresAuth;
    private String requiredAuthHeader;

    private Instant createdAt;
    private Instant updatedAt;
}
