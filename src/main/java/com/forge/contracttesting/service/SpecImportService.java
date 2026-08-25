package com.forge.contracttesting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.contracttesting.dto.EndpointResponse;
import com.forge.contracttesting.dto.GenerateMockServerRequest;
import com.forge.contracttesting.dto.GenerateMockServerResponse;
import com.forge.contracttesting.model.Contract;
import com.forge.contracttesting.model.MockEndpoint;
import com.forge.contracttesting.model.MockServer;
import com.forge.contracttesting.repository.ContractRepository;
import com.forge.contracttesting.repository.MockEndpointRepository;
import com.forge.contracttesting.repository.MockServerRepository;
import com.forge.contracttesting.service.SpecParserService.EndpointInfo;
import com.forge.contracttesting.service.SpecParserService.ParsedSpec;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a MockServer with MockEndpoints from a Contract's uploaded spec.
 * Mirrors SpecImportService in the reference mock-api service, adapted to
 * read the spec from Contract.specRaw via SpecParserService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpecImportService {

    private final ContractRepository contractRepository;
    private final MockServerRepository mockServerRepository;
    private final MockEndpointRepository mockEndpointRepository;
    private final SpecParserService specParserService;
    private final MockServersService mockServersService;
    private final MockEndpointService mockEndpointService;
    private static final Random RANDOM = new Random();
    private final ObjectMapper objectMapper;

    @Value("${mock.service.base-url:http://localhost:8090/mock}")
    private String mockServiceBaseUrl;

    public GenerateMockServerResponse generateFromContract(GenerateMockServerRequest request) {
        if (request.getContractId() == null || request.getContractId().isBlank()) {
            throw new IllegalArgumentException("contractId is required");
        }

        Contract contract = contractRepository.findById(request.getContractId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Contract not found: " + request.getContractId()));

        if (contract.getSpecRaw() == null || contract.getSpecRaw().isBlank()) {
            throw new IllegalArgumentException(
                    "Contract " + request.getContractId() + " has no spec uploaded. " +
                    "Upload a spec file first.");
        }

        // Parse spec endpoints
        ParsedSpec spec = specParserService.parse(contract.getSpecRaw(), contract.getSpecFilename());

        int delayMs = request.getResponseLatencyMs() != null ? request.getResponseLatencyMs() : 0;
        String mockServiceName = request.getMockServiceName() != null
                ? request.getMockServiceName() : contract.getName() + " Mock";

        // Upsert mock server — one per contract
        MockServer mockServer = mockServerRepository.findByContractId(contract.getId())
                .orElse(MockServer.builder()
                        .id(UUID.randomUUID().toString())
                        .requestCount(0L)
                        .createdAt(Instant.now())
                        .build());

        String mockUrl = mockServer.getMockUrl() != null
                ? mockServer.getMockUrl()
                : generateUniqueMockUrl();

        mockServer.setContractId(contract.getId());
        mockServer.setApiSpecName(contract.getSpecFilename() != null
                ? contract.getSpecFilename() : contract.getName());
        mockServer.setName(mockServiceName);
        mockServer.setMockServiceName(mockServiceName);
        mockServer.setMockUrl(mockUrl);
        mockServer.setMockServerUrl(buildRuntimeBaseUrl(mockUrl));
        mockServer.setIsPrivate(request.getIsPrivate() != null ? request.getIsPrivate() : true);
        mockServer.setDelayMs(delayMs);
        mockServer.setResponseLatencyMs(delayMs);
        mockServer.setUpdatedAt(Instant.now());

        MockServer savedServer = mockServerRepository.save(mockServer);
        log.info("Mock server upserted: id={}, url={}", savedServer.getId(), savedServer.getMockUrl());

        // Update Contract.mockServerId so the runtime can find it
        contract.setMockServerId(savedServer.getId());
        contractRepository.save(contract);

        // Delete old endpoints and recreate from spec
        mockEndpointRepository.deleteByMockId(savedServer.getId());

        List<EndpointResponse> endpointResponses = spec.getEndpoints().stream()
                .map(ep -> {
                    String responseBody = buildExampleResponse(ep, spec);
                    String requestBodySample = buildExampleRequest(ep);

                    MockEndpoint endpoint = MockEndpoint.builder()
                            .id(UUID.randomUUID().toString())
                            .mockId(savedServer.getId())
                            .path(normalizePath(ep.getPath()))
                            .method(ep.getMethod())
                            .responseStatus(200)
                            .responseHeaders(Map.of("Content-Type", "application/json"))
                            .responseBody(responseBody)
                            .requestBodySample(requestBodySample)
                            .delayMs(delayMs)
                            .isActive(true)
                            .validationMode("NONE")
                            .validateMethod(true)
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();

                    MockEndpoint saved = mockEndpointRepository.save(endpoint);
                    log.info("Created mock endpoint: {} {}", saved.getMethod(), saved.getPath());
                    return mockEndpointService.mapToResponse(saved);
                })
                .collect(Collectors.toList());

        return GenerateMockServerResponse.builder()
                .mockServer(mockServersService.mapToResponse(savedServer))
                .endpoints(endpointResponses)
                .totalEndpoints(endpointResponses.size())
                .build();
    }

    // ── Example generation from spec schemas ──────────────────────────────────

    private String buildExampleResponse(EndpointInfo ep, ParsedSpec spec) {
        Schema<?> schema = extractResponseSchema(ep);
        boolean wrapAsList = false;

        // Auto-generated/template specs commonly declare responses with only a
        // description ("200": {"description": "List of creates"}) and no content
        // schema, even though the sibling request body is fully typed. Without this
        // fallback every such response silently renders as "{}" while the request
        // body next to it is fully populated.
        if (schema == null) {
            schema = resolveFallbackResponseSchema(ep, spec);
            wrapAsList = schema != null && isListEndpoint(ep);
        }
        if (schema == null) return "{}";

        try {
            // dynamic=true: ID-like fields become {{randomInt}} template tokens so the
            // runtime (MockRuntimeService.renderResponseTemplate) generates a fresh
            // value per call instead of freezing one random ID forever.
            Object example = buildExampleFromSchema(schema, true);
            if (wrapAsList) example = List.of(example);
            return objectMapper.writeValueAsString(example);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Schema<?> extractResponseSchema(EndpointInfo ep) {
        Map<String, ApiResponse> successes = ep.getSuccessResponses();
        if (successes.isEmpty()) return null;

        ApiResponse apiResp = successes.values().iterator().next();
        if (apiResp.getContent() == null) return null;

        MediaType mediaType = apiResp.getContent().get("application/json");
        if (mediaType == null) mediaType = apiResp.getContent().values().stream().findFirst().orElse(null);
        return mediaType != null ? mediaType.getSchema() : null;
    }

    /**
     * Falls back to the request body's schema (POST/PUT typically echo back the
     * resource they just wrote), then to a components.schemas entry matching the
     * path's resource name (e.g. "/creates" -> "Create"), since neither is a
     * proper substitute for a declared response schema but both are far closer
     * to the real shape than an empty object.
     */
    private Schema<?> resolveFallbackResponseSchema(EndpointInfo ep, ParsedSpec spec) {
        Schema<?> requestSchema = extractRequestSchema(ep);
        if (requestSchema != null) return requestSchema;
        return findSchemaByResourceName(ep.getPath(), spec);
    }

    private Schema<?> extractRequestSchema(EndpointInfo ep) {
        if (ep.getOperation() == null || ep.getOperation().getRequestBody() == null) return null;
        var content = ep.getOperation().getRequestBody().getContent();
        if (content == null) return null;

        MediaType mediaType = content.get("application/json");
        if (mediaType == null) mediaType = content.values().stream().findFirst().orElse(null);
        return mediaType != null ? mediaType.getSchema() : null;
    }

    private Schema<?> findSchemaByResourceName(String path, ParsedSpec spec) {
        String resource = firstPathSegment(path);
        if (resource == null) return null;
        String singular = resource.length() > 1 && resource.endsWith("s")
                ? resource.substring(0, resource.length() - 1) : resource;

        for (String candidate : List.of(singular, resource)) {
            for (Map.Entry<String, Schema<?>> entry : spec.getSchemas().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(candidate)) return entry.getValue();
            }
        }
        return null;
    }

    private String firstPathSegment(String path) {
        if (path == null) return null;
        for (String segment : path.split("/")) {
            if (!segment.isBlank() && !segment.startsWith("{")) return segment;
        }
        return null;
    }

    private boolean isListEndpoint(EndpointInfo ep) {
        return "GET".equals(ep.getMethod()) && !ep.getPath().contains("{");
    }

    private String buildExampleRequest(EndpointInfo ep) {
        if (!ep.isMutating()) return null;
        if (ep.getOperation() == null || ep.getOperation().getRequestBody() == null) return "{}";

        var content = ep.getOperation().getRequestBody().getContent();
        if (content == null) return "{}";

        MediaType mediaType = content.get("application/json");
        if (mediaType == null) mediaType = content.values().stream().findFirst().orElse(null);
        if (mediaType == null || mediaType.getSchema() == null) return "{}";

        try {
            // dynamic=false: request samples must stay concrete (they're used as
            // literal comparison values under EXACT_MATCH validation), never a template.
            Object example = buildExampleFromSchema(mediaType.getSchema(), false);
            return objectMapper.writeValueAsString(example);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Object buildExampleFromSchema(Schema<?> schema, boolean dynamic) {
        return buildExampleFromSchema(null, schema, dynamic);
    }

    /**
     * Builds a realistic example value from an OpenAPI schema. Prefers, in order:
     * an explicit `example`, an `enum`'s first value, a `default`, a format-specific
     * value (email/uuid/date-time/...), then a name-based heuristic keyed off the
     * property name (e.g. "customerId" -> "CUST-1234", "status" -> "ACTIVE").
     * Falls back to the literal "string"/0/true only when none of the above apply
     * — i.e. when the spec genuinely gives no hint about what the value should look like.
     *
     * @param dynamic when true (response examples), ID-like fields render as
     *                "{{randomInt}}" template tokens so MockRuntimeService generates
     *                a fresh value per call instead of a value frozen forever.
     */
    private Object buildExampleFromSchema(String propertyName, Schema<?> schema, boolean dynamic) {
        if (schema == null) return Map.of();
        if (schema.getExample() != null) return schema.getExample();
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) return schema.getEnum().get(0);
        if (schema.getDefault() != null) return schema.getDefault();

        // OpenAPI 3.1 specs are parsed as JsonSchema (2020-12 dialect): getType() is
        // always null there and the real type lives in getTypes() instead. Without this
        // fallback every 3.1-authored field (string/integer/etc.) is mistaken for an
        // untyped schema and silently rendered as {} below.
        String type = schema.getType();
        if (type == null && schema.getTypes() != null && !schema.getTypes().isEmpty()) {
            type = schema.getTypes().iterator().next();
        }
        if (type == null && schema.getProperties() != null) type = "object";
        if (type == null) return Map.of();

        return switch (type) {
            case "object" -> {
                Map<String, Object> obj = new LinkedHashMap<>();
                if (schema.getProperties() != null) {
                    schema.getProperties().forEach((name, prop) ->
                            obj.put(name, buildExampleFromSchema(name, (Schema<?>) prop, dynamic)));
                }
                yield obj;
            }
            case "array" -> {
                Schema<?> items = schema.getItems();
                yield items != null ? List.of(buildExampleFromSchema(propertyName, items, dynamic)) : List.of();
            }
            case "string"  -> schema.getFormat() != null
                    ? exampleForFormat(schema.getFormat(), dynamic)
                    : exampleForPropertyName(propertyName, dynamic);
            case "integer" -> 0;
            case "number"  -> 0.0;
            case "boolean" -> true;
            // Unknown/malformed type value (e.g. a spec authored with "type": "format"
            // instead of "type": "string", "format": "email"): still emit a placeholder
            // rather than null, since null is stripped by Jackson's non_null inclusion
            // (application.properties) and would silently drop a declared field from
            // the generated body entirely.
            default        -> exampleForPropertyName(propertyName, dynamic);
        };
    }

    private String exampleForFormat(String format, boolean dynamic) {
        return switch (format) {
            case "date-time" -> dynamic ? "{{timestamp}}" : "2024-01-01T00:00:00Z";
            case "date"      -> "2024-01-01";
            case "email"     -> "user@example.com";
            case "uuid"      -> dynamic ? "{{uuid}}" : "00000000-0000-0000-0000-000000000000";
            case "uri"       -> "https://example.com";
            default          -> "string";
        };
    }

    /** Name-based fallback for unformatted string properties, so specs without explicit
     *  examples/enums still produce plausible values instead of a literal "string". */
    private String exampleForPropertyName(String propertyName, boolean dynamic) {
        if (propertyName == null || propertyName.isBlank()) return "string";
        String lower = propertyName.toLowerCase();

        if (lower.endsWith("id")) {
            String prefix = propertyName.replaceAll("(?i)id$", "").replaceAll("[^A-Za-z]", "");
            String tag = prefix.isBlank() ? "ID" : prefix.substring(0, Math.min(4, prefix.length())).toUpperCase();
            return dynamic ? tag + "-{{randomInt}}" : tag + "-" + (1000 + RANDOM.nextInt(9000));
        }
        if (lower.contains("token"))                          return dynamic ? "{{uuid}}" : "sample-token-value";
        if (lower.contains("email"))                          return "user@example.com";
        if (lower.contains("phone"))                          return "+1-555-0100";
        if (lower.equals("status") || lower.equals("state"))  return "ACTIVE";
        // Checked before the generic "name" match below, since "username" contains
        // "name" as a substring but is a login handle, not a person's display name.
        if (lower.contains("username") || lower.contains("login"))
                                                               return dynamic ? "user{{randomInt}}" : "user" + (1000 + RANDOM.nextInt(9000));
        if (lower.contains("password") || lower.contains("passwd"))
                                                               return "P@ssw0rd123";
        if (lower.contains("name"))                           return "Jane Doe";
        if (lower.contains("address"))                        return "123 Main St";
        if (lower.contains("url") || lower.contains("uri"))   return "https://example.com";
        return "string";
    }

    private String generateUniqueMockUrl() {
        String url;
        do { url = "mock-" + UUID.randomUUID().toString().substring(0, 8); }
        while (mockServerRepository.existsByMockUrl(url));
        return url;
    }

    private String buildRuntimeBaseUrl(String mockUrl) {
        String base = mockServiceBaseUrl != null && !mockServiceBaseUrl.isBlank()
                ? mockServiceBaseUrl.trim() : "http://localhost:8090/mock";
        return base.replaceAll("/+$", "") + "/" + mockUrl;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        return "/" + path.replaceAll("^/+", "");
    }
}
