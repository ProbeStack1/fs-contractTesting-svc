package com.forge.contracttesting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.forge.contracttesting.dto.EndpointExecutionSummary;
import com.forge.contracttesting.dto.MockServerRunResult;
import com.forge.contracttesting.exception.MockServerNotFoundException;
import com.forge.contracttesting.model.Consumer;
import com.forge.contracttesting.model.MockEndpoint;
import com.forge.contracttesting.model.MockRequestLog;
import com.forge.contracttesting.model.MockServer;
import com.forge.contracttesting.repository.ConsumerRepository;
import com.forge.contracttesting.repository.MockEndpointRepository;
import com.forge.contracttesting.repository.MockRequestLogRepository;
import com.forge.contracttesting.repository.MockServerRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MockRuntimeService {

    private final MockServerRepository mockServerRepository;
    private final MockEndpointRepository mockEndpointRepository;
    private final MockRequestLogRepository mockRequestLogRepository;
    private final ConsumerRepository consumerRepository;
    private final ObjectMapper objectMapper;

    @Value("${mock.service.base-url:http://localhost:8090/mock}")
    private String mockServiceBaseUrl;

    // Sliding-window rate limit buckets: key = "mockId:consumerName", value = [windowStartMs, count]
    private final ConcurrentHashMap<String, long[]> rateLimitBuckets = new ConcurrentHashMap<>();

    private record ValidationResult(boolean valid, String expectedBody) {}

    // ── Runtime request handler ───────────────────────────────────────────────

    public ResponseEntity<Object> handleMockRequest(
            String mockUrl, String path, String method,
            String requestBody, HttpServletRequest request) {

        long startTime = System.currentTimeMillis();
        String normalizedPath = normalizePath(path);
        log.debug("Handling {} request for mock: {}, path: {}", method, mockUrl, normalizedPath);

        MockServer mockServer = mockServerRepository.findByMockUrl(mockUrl)
                .orElse(null);

        if (mockServer == null) {
            log.warn("Mock server not found: {}", mockUrl);
            return createErrorResponse(HttpStatus.NOT_FOUND, "Mock server not found: " + mockUrl);
        }

        applyDelay(mockServer.getDelayMs());

        // Rate-limit check using Consumer.apiTps (Gap 11)
        String incomingConsumer = request.getHeader("x-consumer-name");
        if (incomingConsumer != null && !incomingConsumer.isBlank()
                && isRateLimited(incomingConsumer, mockServer.getId())) {
            log.warn("Rate limit exceeded for consumer={} on mock={}", incomingConsumer, mockUrl);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createErrorJson("Rate limit exceeded",
                            "Consumer " + incomingConsumer + " has exceeded the allowed requests per second"));
        }

        // Exact match first, then path-param pattern match
        Optional<MockEndpoint> endpointOpt = mockEndpointRepository
                .findByMockIdAndPathAndMethod(mockServer.getId(), normalizedPath, method.toUpperCase());

        if (endpointOpt.isEmpty()) {
            List<MockEndpoint> allEndpoints = mockEndpointRepository.findByMockId(mockServer.getId());
            log.warn("Exact match miss for [{} {}] on mockId={}. Total endpoints stored: {}. Available: [{}]",
                    method.toUpperCase(), normalizedPath, mockServer.getId(),
                    allEndpoints.size(),
                    allEndpoints.stream()
                            .map(e -> e.getMethod() + " " + e.getPath())
                            .collect(java.util.stream.Collectors.joining(", ")));
            endpointOpt = allEndpoints.stream()
                    .filter(e -> e.getMethod().equalsIgnoreCase(method)
                            && pathMatchesPattern(normalizedPath, e.getPath()))
                    .findFirst();
        }

        int responseStatus;
        String responseBody;
        ResponseEntity<Object> response;

        if (endpointOpt.isPresent() && Boolean.TRUE.equals(endpointOpt.get().getIsActive())) {
            MockEndpoint endpoint = endpointOpt.get();

            if (!isMethodAllowed(endpoint, method)) {
                return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                        .header("Allow", endpoint.getMethod())
                        .body(createErrorJson("Method not allowed",
                                "Expected method: " + endpoint.getMethod()));
            }

            if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                    || "PATCH".equalsIgnoreCase(method)) {
                ValidationResult vr = validateRequestBody(requestBody, endpoint);
                if (!vr.valid()) {
                    String msg = "Request body does not match expected pattern";
                    if (vr.expectedBody() != null) msg += ".\nExpected:\n" + vr.expectedBody();
                    return ResponseEntity.badRequest()
                            .body(createErrorJson(msg, "Validation failed"));
                }
            }

            applyDelay(endpoint.getDelayMs());
            responseStatus = endpoint.getResponseStatus() != null ? endpoint.getResponseStatus() : 200;
            responseBody   = endpoint.getResponseBody();

            HttpHeaders headers = new HttpHeaders();
            if (endpoint.getResponseHeaders() != null) {
                endpoint.getResponseHeaders().forEach(headers::add);
            }
            headers.setContentType(MediaType.APPLICATION_JSON);
            response = ResponseEntity.status(responseStatus).headers(headers).body(responseBody);

        } else {
            responseStatus = HttpStatus.NOT_FOUND.value();
            responseBody   = createDefaultNotFoundResponse(mockUrl, normalizedPath, method);
            response = ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(responseBody);
        }

        long responseTime = System.currentTimeMillis() - startTime;
        logMockRequest(mockServer, normalizedPath, method, requestBody,
                responseStatus, responseBody, responseTime, request);
        incrementRequestCount(mockServer);

        return response;
    }

    // ── Run all active endpoints ──────────────────────────────────────────────

    public MockServerRunResult runMockServer(String mockServerId) {
        MockServer mockServer = mockServerRepository.findById(mockServerId)
                .orElseThrow(() -> new MockServerNotFoundException(
                        "Mock server not found with ID: " + mockServerId));

        List<MockEndpoint> endpoints = mockEndpointRepository.findByMockIdAndIsActiveTrue(mockServerId);
        List<EndpointExecutionSummary> summaries = new ArrayList<>();
        int success = 0, failure = 0;

        for (MockEndpoint endpoint : endpoints) {
            EndpointExecutionSummary summary = executeEndpoint(mockServer, endpoint);
            summaries.add(summary);
            if (summary.isSuccess()) success++;
            else failure++;
        }

        return MockServerRunResult.builder()
                .mockServerId(mockServerId)
                .mockServerName(mockServer.getName())
                .totalEndpoints(endpoints.size())
                .successCount(success)
                .failureCount(failure)
                .results(summaries)
                .build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private EndpointExecutionSummary executeEndpoint(MockServer mockServer, MockEndpoint endpoint) {
        long start = System.currentTimeMillis();
        String resolvedPath = endpoint.getPath().replaceAll("\\{[^}]+}", "1");
        String method = endpoint.getMethod();

        String requestBody = null;
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)) {
            requestBody = (endpoint.getRequestBodySample() != null
                    && !endpoint.getRequestBodySample().isBlank())
                    ? endpoint.getRequestBodySample() : "{}";
        }

        try {
            applyDelay(endpoint.getDelayMs());
            long responseTimeMs = System.currentTimeMillis() - start;
            int statusCode = endpoint.getResponseStatus() != null ? endpoint.getResponseStatus() : 200;
            String responseBodyStr = endpoint.getResponseBody();

            logExecution(mockServer, resolvedPath, method, requestBody,
                    statusCode, responseBodyStr, responseTimeMs);

            log.info("Executed {} {} -> {} ({}ms)", method, resolvedPath, statusCode, responseTimeMs);

            return EndpointExecutionSummary.builder()
                    .endpointId(endpoint.getId())
                    .method(method)
                    .path(endpoint.getPath())
                    .statusCode(statusCode)
                    .statusText(getStatusText(statusCode))
                    .responseTimeMs(responseTimeMs)
                    .success(statusCode >= 200 && statusCode < 300)
                    .requestBody(requestBody)
                    .responseBody(responseBodyStr)
                    .build();

        } catch (Exception e) {
            long responseTimeMs = System.currentTimeMillis() - start;
            log.error("Failed to execute {} {}: {}", method, resolvedPath, e.getMessage());
            return EndpointExecutionSummary.builder()
                    .endpointId(endpoint.getId())
                    .method(method)
                    .path(endpoint.getPath())
                    .statusCode(0)
                    .statusText("Error")
                    .responseTimeMs(responseTimeMs)
                    .success(false)
                    .requestBody(requestBody)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private void logExecution(MockServer mockServer, String path, String method,
                              String requestBody, int responseStatus,
                              String responseBody, long responseTimeMs) {
        try {
            mockRequestLogRepository.save(MockRequestLog.builder()
                    .id(UUID.randomUUID().toString())
                    .mockId(mockServer.getId())
                    .mockUrl(mockServer.getMockUrl())
                    .contractId(mockServer.getContractId())
                    .path(path)
                    .method(method)
                    .requestBody(requestBody)
                    .responseStatus(responseStatus)
                    .responseBody(responseBody)
                    .responseTimeMs(responseTimeMs)
                    .timestamp(Instant.now())
                    .build());
            incrementRequestCount(mockServer);
        } catch (Exception e) {
            log.error("Failed to log execution: {}", e.getMessage());
        }
    }

    private ValidationResult validateRequestBody(String incomingBody, MockEndpoint endpoint) {
        String mode = endpoint.getValidationMode();
        if (mode == null || "NONE".equals(mode)) return new ValidationResult(true, null);

        String sample = endpoint.getRequestBodySample();
        if (sample == null || sample.isBlank()) return new ValidationResult(true, null);

        if ("EXACT_MATCH".equals(mode)) {
            boolean valid = normalizeJson(incomingBody).equals(normalizeJson(sample));
            return new ValidationResult(valid, prettyPrintJson(sample));
        }
        if ("JSON_SCHEMA".equals(mode)) {
            String schemaJson = endpoint.getValidationSchema();
            if (schemaJson == null || schemaJson.isBlank()) return new ValidationResult(true, null);
            return new ValidationResult(validateJsonSchema(incomingBody, schemaJson), null);
        }
        return new ValidationResult(true, null);
    }

    private String normalizeJson(String json) {
        if (json == null) return "";
        return json.trim().replaceAll("\\s+", "");
    }

    private boolean validateJsonSchema(String json, String schemaJson) {
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            JsonNode jsonNode  = objectMapper.readTree(json);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            JsonSchema schema = factory.getSchema(schemaNode);
            Set<ValidationMessage> errors = schema.validate(jsonNode);
            return errors.isEmpty();
        } catch (Exception e) {
            log.error("JSON schema validation error: {}", e.getMessage());
            return false;
        }
    }

    private String prettyPrintJson(String json) {
        if (json == null) return "";
        try {
            ObjectMapper pretty = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            return pretty.writeValueAsString(pretty.readTree(json));
        } catch (Exception e) { return json; }
    }

    private boolean pathMatchesPattern(String actualPath, String patternPath) {
        String regex = patternPath.replaceAll("\\{[^}]+}", "[^/]+");
        return actualPath.matches(regex);
    }

    private void applyDelay(Integer delayMs) {
        if (delayMs != null && delayMs > 0) {
            try { Thread.sleep(delayMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        return "/" + path.replaceAll("^/+", "");
    }

    private void logMockRequest(MockServer mockServer, String path, String method,
                                 String requestBody, int responseStatus, String responseBody,
                                 long responseTime, HttpServletRequest request) {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            Enumeration<String> names = request.getHeaderNames();
            if (names != null) {
                while (names.hasMoreElements()) {
                    String name = names.nextElement();
                    headers.put(name, request.getHeader(name));
                }
            }

            // Extract consumer identity (Gap 3)
            String consumerName    = headers.getOrDefault("x-consumer-name", "unknown");
            String consumerVersion = headers.getOrDefault("x-consumer-version", "1.0.0");
            String sessionId       = headers.getOrDefault("x-session-id",
                    UUID.randomUUID().toString());

            mockRequestLogRepository.save(MockRequestLog.builder()
                    .id(UUID.randomUUID().toString())
                    .mockId(mockServer.getId())
                    .mockUrl(mockServer.getMockUrl())
                    .contractId(mockServer.getContractId())
                    .path(path)
                    .method(method)
                    .requestHeaders(headers)
                    .requestBody(requestBody)
                    .responseStatus(responseStatus)
                    .responseBody(responseBody)
                    .responseTimeMs(responseTime)
                    .consumerName(consumerName)
                    .consumerVersion(consumerVersion)
                    .sessionId(sessionId)
                    .timestamp(Instant.now())
                    .build());
        } catch (Exception e) {
            log.error("Failed to log mock request: {}", e.getMessage());
        }
    }

    private void incrementRequestCount(MockServer mockServer) {
        try {
            long count = mockServer.getRequestCount() != null ? mockServer.getRequestCount() : 0L;
            mockServer.setRequestCount(count + 1);
            mockServer.setUpdatedAt(Instant.now());
            mockServerRepository.save(mockServer);
        } catch (Exception e) {
            log.error("Failed to increment request count: {}", e.getMessage());
        }
    }

    /**
     * Sliding-window rate limiter.
     * Returns true (= block request) when the consumer has exceeded Consumer.apiTps
     * requests within the current 1-second window.
     */
    private boolean isRateLimited(String consumerName, String mockId) {
        List<Consumer> consumers = consumerRepository.findByConsumerName(consumerName);
        if (consumers.isEmpty()) return false;

        Consumer consumer = consumers.get(0);
        if (!"true".equalsIgnoreCase(consumer.getRateLimiting())) return false;

        int tps;
        try { tps = Integer.parseInt(consumer.getApiTps()); }
        catch (NumberFormatException e) { return false; }
        if (tps <= 0) return false;

        String key = mockId + ":" + consumerName;
        long now = System.currentTimeMillis();

        long[] bucket = rateLimitBuckets.compute(key, (k, v) -> {
            if (v == null || now - v[0] >= 1000L) return new long[]{now, 1};
            return new long[]{v[0], v[1] + 1};
        });

        return bucket[1] > tps;
    }

    private boolean isMethodAllowed(MockEndpoint endpoint, String method) {
        if (!Boolean.TRUE.equals(endpoint.getValidateMethod())) return true;
        return endpoint.getMethod().equalsIgnoreCase(method);
    }

    private ResponseEntity<Object> createErrorResponse(HttpStatus status, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", status.value());
        error.put("error", status.getReasonPhrase());
        error.put("message", message);
        error.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(error);
    }

    private String createErrorJson(String error, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", error, "message", message));
        } catch (Exception e) {
            return "{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}";
        }
    }

    private String createDefaultNotFoundResponse(String mockUrl, String path, String method) {
        return String.format("""
            {
                "error": "Endpoint not found",
                "message": "No mock endpoint configured for %s %s on mock server '%s'",
                "hint": "Configure an endpoint for this path and method in your mock server settings"
            }
            """, method, path, mockUrl);
    }

    private String getStatusText(int statusCode) {
        try { return HttpStatus.valueOf(statusCode).getReasonPhrase(); }
        catch (IllegalArgumentException e) { return "Unknown"; }
    }

    public String buildMockServerUrl(String mockUrl) {
        String base = mockServiceBaseUrl != null && !mockServiceBaseUrl.isBlank()
                ? mockServiceBaseUrl.trim() : "http://localhost:8090/mock";
        return base.replaceAll("/+$", "") + "/" + mockUrl;
    }
}
