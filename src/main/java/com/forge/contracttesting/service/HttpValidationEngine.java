package com.forge.contracttesting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.contracttesting.dto.TestCaseResult;
import com.forge.contracttesting.dto.ValidationViolation;
import com.forge.contracttesting.model.Contract;
import com.forge.contracttesting.service.SpecParserService.EndpointInfo;
import com.forge.contracttesting.service.SpecParserService.ParsedSpec;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls the real running service at contract.baseUrl and compares actual
 * HTTP responses against what the spec says they should look like.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpValidationEngine {

    private final ObjectMapper objectMapper;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT  = Duration.ofSeconds(30);

    public List<TestCaseResult> validateLive(Contract contract, ParsedSpec spec) {
        List<TestCaseResult> results = new ArrayList<>();

        if (contract.getBaseUrl() == null || contract.getBaseUrl().isBlank()) {
            TestCaseResult r = new TestCaseResult();
            r.setTestId("HTTP-CONFIG");
            r.setTestName("Live HTTP validation");
            r.setCategory("http");
            r.addViolation(new ValidationViolation("baseUrl", "configured URL", "null",
                    "Contract has no baseUrl — set it to enable live validation", "error"));
            results.add(r);
            return results;
        }

        HttpClient client = buildHttpClient(contract.getBaseUrl());

        for (EndpointInfo ep : spec.getEndpoints()) {
            TestCaseResult result = validateEndpoint(client, contract, ep, spec);
            results.add(result);
        }

        return results;
    }

    private TestCaseResult validateEndpoint(HttpClient client, Contract contract,
                                             EndpointInfo ep, ParsedSpec spec) {
        String testId = "HTTP-" + ep.getMethod() + "-" + ep.getPath().replace("/", "_");
        TestCaseResult result = new TestCaseResult();
        result.setTestId(testId);
        result.setTestName("Live: " + ep.getMethod() + " " + ep.getPath());
        result.setCategory("http");
        result.setPassed(true);

        // Fill path params with example values from spec
        String resolvedPath = resolvePath(ep.getPath(), ep);
        String url = contract.getBaseUrl().replaceAll("/$", "") + resolvedPath;

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .method(ep.getMethod(), HttpRequest.BodyPublishers.noBody());

            injectAuth(reqBuilder, contract);
            reqBuilder.header("Accept", "application/json");

            HttpRequest request = reqBuilder.build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            compareStatus(result, ep, response.statusCode());
            compareBody(result, ep, response.body());

        } catch (Exception e) {
            log.warn("HTTP call failed for {} {}: {}", ep.getMethod(), url, e.getMessage());
            result.addViolation(new ValidationViolation(
                    ep.getMethod() + " " + url, "reachable service", "connection error",
                    "Could not reach " + url + ": " + e.getMessage(), "error"
            ));
        }

        return result;
    }

    private void compareStatus(TestCaseResult result, EndpointInfo ep, int actualStatus) {
        Map<String, ApiResponse> successes = ep.getSuccessResponses();
        if (successes.isEmpty()) return;

        boolean statusMatch = successes.keySet().stream().anyMatch(code -> {
            try { return Integer.parseInt(code) == actualStatus; }
            catch (NumberFormatException e) { return false; }
        });

        if (!statusMatch) {
            String expected = String.join(" or ", successes.keySet());
            result.addViolation(new ValidationViolation(
                    ep.getMethod() + " " + ep.getPath(),
                    "status " + expected,
                    "status " + actualStatus,
                    "Actual HTTP status does not match any expected 2xx status from spec",
                    "error"
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private void compareBody(TestCaseResult result, EndpointInfo ep, String actualBody) {
        if (actualBody == null || actualBody.isBlank()) return;

        Map<String, ApiResponse> successes = ep.getSuccessResponses();
        if (successes.isEmpty()) return;

        ApiResponse specResp = successes.values().iterator().next();
        if (specResp.getContent() == null) return;

        MediaType mediaType = specResp.getContent().get("application/json");
        if (mediaType == null) mediaType = specResp.getContent().get("*/*");
        if (mediaType == null || mediaType.getSchema() == null) return;

        Schema<?> schema = mediaType.getSchema();

        try {
            JsonNode actualJson = objectMapper.readTree(actualBody);
            checkSchemaShape(result, ep.getMethod() + " " + ep.getPath(), schema, actualJson, "");
        } catch (Exception e) {
            result.addViolation(new ValidationViolation(
                    ep.getMethod() + " " + ep.getPath(),
                    "valid JSON body", "parse error",
                    "Could not parse actual response body as JSON: " + e.getMessage(), "error"
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private void checkSchemaShape(TestCaseResult result, String location,
                                   Schema<?> schema, JsonNode actual, String prefix) {
        if (schema == null || actual == null) return;

        // Check required fields
        List<String> required = schema.getRequired();
        if (required != null && actual.isObject()) {
            for (String req : required) {
                if (!actual.has(req) || actual.get(req).isNull()) {
                    result.addViolation(new ValidationViolation(
                            location + " > " + prefix + req,
                            "required field present",
                            "missing or null",
                            "Required field \"" + req + "\" is missing or null in actual response",
                            "error"
                    ));
                }
            }
        }

        // Check property types
        Map<String, Schema> properties = schema.getProperties();
        if (properties != null && actual.isObject()) {
            properties.forEach((fieldName, fieldSchema) -> {
                JsonNode fieldValue = actual.get(fieldName);
                if (fieldValue == null || fieldValue.isNull()) return;

                String expectedType = ((Schema<?>) fieldSchema).getType();
                if (expectedType == null) return;

                boolean typeMatch = switch (expectedType) {
                    case "string"  -> fieldValue.isTextual();
                    case "integer" -> fieldValue.isIntegralNumber();
                    case "number"  -> fieldValue.isNumber();
                    case "boolean" -> fieldValue.isBoolean();
                    case "array"   -> fieldValue.isArray();
                    case "object"  -> fieldValue.isObject();
                    default        -> true;
                };

                if (!typeMatch) {
                    result.addViolation(new ValidationViolation(
                            location + " > " + prefix + fieldName,
                            expectedType,
                            fieldValue.getNodeType().name().toLowerCase(),
                            "Field \"" + fieldName + "\" has wrong type in actual response",
                            "error"
                    ));
                }
            });
        }
    }

    private String resolvePath(String path, EndpointInfo ep) {
        // Replace path params with example values: /users/{id} → /users/1
        return path.replaceAll("\\{[^}]+}", "1");
    }

    private void injectAuth(HttpRequest.Builder builder, Contract contract) {
        if (contract.getAuthType() == null || "NONE".equalsIgnoreCase(contract.getAuthType())) return;
        if (contract.getAuthValue() == null || contract.getAuthValue().isBlank()) return;

        if ("BEARER".equalsIgnoreCase(contract.getAuthType())) {
            builder.header("Authorization", "Bearer " + contract.getAuthValue());
        } else if ("API_KEY".equalsIgnoreCase(contract.getAuthType())) {
            builder.header("X-Api-Key", contract.getAuthValue());
        }
    }

    private HttpClient buildHttpClient(String baseUrl) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);

        // Skip SSL verification for internal/staging URLs
        if (baseUrl.startsWith("https://")) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, null);
                builder.sslContext(sslContext);
            } catch (Exception e) {
                log.warn("Could not configure SSL context: {}", e.getMessage());
            }
        }

        return builder.build();
    }

    private static class TrustAllManager implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
