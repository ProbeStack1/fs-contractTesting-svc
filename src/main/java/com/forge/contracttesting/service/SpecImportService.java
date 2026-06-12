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
                    String responseBody = buildExampleResponse(ep);
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

    private String buildExampleResponse(EndpointInfo ep) {
        Map<String, ApiResponse> successes = ep.getSuccessResponses();
        if (successes.isEmpty()) return "{}";

        ApiResponse apiResp = successes.values().iterator().next();
        if (apiResp.getContent() == null) return "{}";

        MediaType mediaType = apiResp.getContent().get("application/json");
        if (mediaType == null) mediaType = apiResp.getContent().values().stream().findFirst().orElse(null);
        if (mediaType == null || mediaType.getSchema() == null) return "{}";

        try {
            Object example = buildExampleFromSchema(mediaType.getSchema());
            return objectMapper.writeValueAsString(example);
        } catch (Exception e) {
            return "{}";
        }
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
            Object example = buildExampleFromSchema(mediaType.getSchema());
            return objectMapper.writeValueAsString(example);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Object buildExampleFromSchema(Schema<?> schema) {
        if (schema == null) return Map.of();
        if (schema.getExample() != null) return schema.getExample();

        String type = schema.getType();
        if (type == null && schema.getProperties() != null) type = "object";
        if (type == null) return Map.of();

        return switch (type) {
            case "object" -> {
                Map<String, Object> obj = new LinkedHashMap<>();
                if (schema.getProperties() != null) {
                    schema.getProperties().forEach((name, prop) ->
                            obj.put(name, buildExampleFromSchema((Schema<?>) prop)));
                }
                yield obj;
            }
            case "array" -> {
                Schema<?> items = schema.getItems();
                yield items != null ? List.of(buildExampleFromSchema(items)) : List.of();
            }
            case "string"  -> schema.getFormat() != null ? exampleForFormat(schema.getFormat()) : "string";
            case "integer" -> 0;
            case "number"  -> 0.0;
            case "boolean" -> true;
            default        -> null;
        };
    }

    private String exampleForFormat(String format) {
        return switch (format) {
            case "date-time" -> "2024-01-01T00:00:00Z";
            case "date"      -> "2024-01-01";
            case "email"     -> "user@example.com";
            case "uuid"      -> "00000000-0000-0000-0000-000000000000";
            case "uri"       -> "https://example.com";
            default          -> "string";
        };
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
