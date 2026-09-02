package com.forge.contracttesting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.contracttesting.dto.*;
import com.forge.contracttesting.exception.EndpointAlreadyExistsException;
import com.forge.contracttesting.exception.EndpointNotFoundException;
import com.forge.contracttesting.exception.MockServerNotFoundException;
import com.forge.contracttesting.model.MockEndpoint;
import com.forge.contracttesting.model.MockRequestLog;
import com.forge.contracttesting.repository.MockEndpointRepository;
import com.forge.contracttesting.repository.MockRequestLogRepository;
import com.forge.contracttesting.repository.MockServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MockEndpointService {

    private final MockServerRepository mockServerRepository;
    private final MockEndpointRepository mockEndpointRepository;
    private final MockRequestLogRepository mockRequestLogRepository;
    private final ObjectMapper objectMapper;

    public List<EndpointResponse> listEndpoints(String mockId) {
        if (!mockServerRepository.existsById(mockId)) {
            throw new MockServerNotFoundException("Mock server not found with ID: " + mockId);
        }
        return mockEndpointRepository.findByMockId(mockId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public EndpointResponse createEndpoint(String mockId, CreateEndpointRequest request) {
        if (!mockServerRepository.existsById(mockId)) {
            throw new MockServerNotFoundException("Mock server not found with ID: " + mockId);
        }

        String methodUpper = request.getMethod().toUpperCase();
        String normalizedPath = normalizePath(request.getPath());
        if (mockEndpointRepository.existsByMockIdAndPathAndMethod(
                mockId, normalizedPath, methodUpper)) {
            throw new EndpointAlreadyExistsException(
                    String.format("Endpoint already exists for %s %s", methodUpper, normalizedPath));
        }

        validateBodySample(request.getRequestBodySample(),
                request.getValidationMode(), request.getValidationSchema());

        MockEndpoint endpoint = MockEndpoint.builder()
                .id(UUID.randomUUID().toString())
                .mockId(mockId)
                .path(normalizedPath)
                .method(methodUpper)
                .responseStatus(request.getResponseStatus() != null ? request.getResponseStatus() : 200)
                .responseHeaders(request.getResponseHeaders())
                .responseBody(request.getResponseBody())
                .delayMs(request.getDelayMs() != null ? request.getDelayMs() : 0)
                .isActive(true)
                .requestBodySample(request.getRequestBodySample())
                .validationMode(request.getValidationMode() != null
                        ? request.getValidationMode() : "NONE")
                .validationSchema(request.getValidationSchema())
                .validateMethod(request.getValidateMethod() != null
                        ? request.getValidateMethod() : true)
                .requiresAuth(request.getRequiresAuth())
                .requiredAuthHeader(request.getRequiredAuthHeader())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        MockEndpoint saved = mockEndpointRepository.save(endpoint);
        log.info("Created endpoint: id={}, mock={}, {} {}", saved.getId(), mockId, methodUpper, request.getPath());
        return mapToResponse(saved);
    }

    public EndpointResponse getEndpointById(String endpointId) {
        return mapToResponse(findOrThrow(endpointId));
    }

    public EndpointResponse updateEndpoint(String endpointId, UpdateEndpointRequest request) {
        MockEndpoint endpoint = findOrThrow(endpointId);
        boolean updated = false;

        if (request.getPath()              != null) { endpoint.setPath(normalizePath(request.getPath()));                 updated = true; }
        if (request.getMethod()            != null) { endpoint.setMethod(request.getMethod().toUpperCase());              updated = true; }
        if (request.getResponseStatus()    != null) { endpoint.setResponseStatus(request.getResponseStatus());           updated = true; }
        if (request.getResponseHeaders()   != null) { endpoint.setResponseHeaders(request.getResponseHeaders());         updated = true; }
        if (request.getResponseBody()      != null) { endpoint.setResponseBody(request.getResponseBody());               updated = true; }
        if (request.getDelayMs()           != null) { endpoint.setDelayMs(request.getDelayMs());                         updated = true; }
        if (request.getIsActive()          != null) { endpoint.setIsActive(request.getIsActive());                       updated = true; }
        if (request.getRequestBodySample() != null) { endpoint.setRequestBodySample(request.getRequestBodySample());     updated = true; }
        if (request.getValidationMode()    != null) { endpoint.setValidationMode(request.getValidationMode());           updated = true; }
        if (request.getValidationSchema()  != null) { endpoint.setValidationSchema(request.getValidationSchema());       updated = true; }
        if (request.getValidateMethod()    != null) { endpoint.setValidateMethod(request.getValidateMethod());           updated = true; }
        if (request.getRequiresAuth()      != null) { endpoint.setRequiresAuth(request.getRequiresAuth());               updated = true; }
        if (request.getRequiredAuthHeader() != null) { endpoint.setRequiredAuthHeader(request.getRequiredAuthHeader());  updated = true; }

        if (updated) {
            validateBodySample(endpoint.getRequestBodySample(),
                    endpoint.getValidationMode(), endpoint.getValidationSchema());
            endpoint.setUpdatedAt(Instant.now());
            mockEndpointRepository.save(endpoint);
        }
        log.info("Updated endpoint: {}", endpointId);
        return mapToResponse(endpoint);
    }

    public void deleteEndpoint(String endpointId) {
        if (!mockEndpointRepository.existsById(endpointId)) {
            throw new EndpointNotFoundException("Endpoint not found with ID: " + endpointId);
        }
        mockEndpointRepository.deleteById(endpointId);
        log.info("Deleted endpoint: {}", endpointId);
    }

    public EndpointResponse toggleEndpoint(String endpointId) {
        MockEndpoint endpoint = findOrThrow(endpointId);
        endpoint.setIsActive(!Boolean.TRUE.equals(endpoint.getIsActive()));
        endpoint.setUpdatedAt(Instant.now());
        mockEndpointRepository.save(endpoint);
        log.info("Toggled endpoint {} isActive to {}", endpointId, endpoint.getIsActive());
        return mapToResponse(endpoint);
    }

    public List<MockRequestLogResponse> getEndpointLogs(String endpointId,
                                                         Integer limit, Integer offset) {
        MockEndpoint endpoint = findOrThrow(endpointId);

        int pageSize   = (limit != null && limit > 0) ? Math.min(limit, 100) : 50;
        int pageNumber = (offset != null && offset >= 0) ? offset / pageSize : 0;
        Pageable pageable = PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Direction.DESC, "timestamp"));

        Page<MockRequestLog> page = mockRequestLogRepository
                .findByMockIdAndPathAndMethodOrderByTimestampDesc(
                        endpoint.getMockId(), endpoint.getPath(), endpoint.getMethod(), pageable);

        return page.getContent().stream()
                .map(l -> MockRequestLogResponse.builder()
                        .id(l.getId())
                        .timestamp(l.getTimestamp())
                        .method(l.getMethod())
                        .path(l.getPath())
                        .responseStatus(l.getResponseStatus())
                        .responseTimeMs(l.getResponseTimeMs())
                        .requestBody(l.getRequestBody())
                        .responseBody(l.getResponseBody())
                        .requestHeaders(l.getRequestHeaders())
                        .consumerName(l.getConsumerName())
                        .consumerVersion(l.getConsumerVersion())
                        .sessionId(l.getSessionId())
                        .build())
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        return "/" + path.replaceAll("^/+", "");
    }

    private MockEndpoint findOrThrow(String endpointId) {
        return mockEndpointRepository.findById(endpointId)
                .orElseThrow(() -> new EndpointNotFoundException(
                        "Endpoint not found with ID: " + endpointId));
    }

    private void validateBodySample(String sample, String validationMode, String schema) {
        if (sample == null || sample.isBlank()) return;
        if ("EXACT_MATCH".equals(validationMode) || "JSON_SCHEMA".equals(validationMode)) {
            try { objectMapper.readTree(sample); }
            catch (Exception e) {
                throw new IllegalArgumentException(
                        "Invalid JSON in requestBodySample for " + validationMode + " mode");
            }
        }
    }

    EndpointResponse mapToResponse(MockEndpoint entity) {
        return EndpointResponse.builder()
                .id(entity.getId())
                .mockId(entity.getMockId())
                .path(entity.getPath())
                .method(entity.getMethod())
                .responseStatus(entity.getResponseStatus())
                .responseHeaders(entity.getResponseHeaders())
                .responseBody(entity.getResponseBody())
                .delayMs(entity.getDelayMs())
                .isActive(entity.getIsActive())
                .requestBodySample(entity.getRequestBodySample())
                .validationMode(entity.getValidationMode())
                .validationSchema(entity.getValidationSchema())
                .validateMethod(entity.getValidateMethod())
                .requiresAuth(entity.getRequiresAuth())
                .requiredAuthHeader(entity.getRequiredAuthHeader())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
