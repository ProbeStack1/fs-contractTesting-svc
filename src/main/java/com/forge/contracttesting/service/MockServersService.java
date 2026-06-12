package com.forge.contracttesting.service;

import com.forge.contracttesting.dto.*;
import com.forge.contracttesting.exception.MockServerNotFoundException;
import com.forge.contracttesting.model.MockRequestLog;
import com.forge.contracttesting.model.MockServer;
import com.forge.contracttesting.repository.MockEndpointRepository;
import com.forge.contracttesting.repository.MockRequestLogRepository;
import com.forge.contracttesting.repository.MockServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class MockServersService {

    private final MockServerRepository mockServerRepository;
    private final MockEndpointRepository mockEndpointRepository;
    private final MockRequestLogRepository mockRequestLogRepository;

    @Value("${mock.service.base-url:http://localhost:8090/mock}")
    private String mockServiceBaseUrl;

    public MockServerResponse createMock(CreateMockServerRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }

        String mockUrl = generateUniqueMockUrl();

        MockServer mockServer = MockServer.builder()
                .id(UUID.randomUUID().toString())
                .contractId(request.getContractId())
                .microserviceId(request.getMicroserviceId())
                .name(request.getName())
                .mockUrl(mockUrl)
                .mockServerUrl(buildRuntimeBaseUrl(mockUrl))
                .isPrivate(request.getIsPrivate() != null ? request.getIsPrivate() : true)
                .delayMs(request.getDelayMs() != null ? request.getDelayMs() : 0)
                .requestCount(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        MockServer saved = mockServerRepository.save(mockServer);
        log.info("Created mock server: id={}, url={}", saved.getId(), saved.getMockUrl());
        return mapToResponse(saved);
    }

    public List<MockServerResponse> listMocks(String microserviceId, Integer limit, Integer offset) {
        int pageSize   = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;
        int pageNumber = (offset != null && offset >= 0) ? offset / pageSize : 0;
        Pageable pageable = PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        List<MockServer> mocks = microserviceId != null
                ? mockServerRepository.findByMicroserviceId(microserviceId, pageable)
                : mockServerRepository.findAll(pageable).getContent();

        return mocks.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    public MockServerResponse getMockById(String mockId) {
        return mapToResponse(findOrThrow(mockId));
    }

    public MockServerResponse getMockByUrl(String mockUrl) {
        MockServer server = mockServerRepository.findByMockUrl(mockUrl)
                .orElseThrow(() -> new MockServerNotFoundException(
                        "Mock server not found with url: " + mockUrl));
        return mapToResponse(server);
    }

    public MockServerResponse updateMock(String mockId, UpdateMockServerRequest request) {
        MockServer mockServer = findOrThrow(mockId);
        boolean updated = false;

        if (request.getName()      != null) { mockServer.setName(request.getName());           updated = true; }
        if (request.getIsPrivate() != null) { mockServer.setIsPrivate(request.getIsPrivate()); updated = true; }
        if (request.getDelayMs()   != null) { mockServer.setDelayMs(request.getDelayMs());     updated = true; }

        if (updated) {
            mockServer.setUpdatedAt(Instant.now());
            mockServerRepository.save(mockServer);
        }
        log.info("Updated mock server: {}", mockId);
        return mapToResponse(mockServer);
    }

    public void deleteMock(String mockId) {
        if (!mockServerRepository.existsById(mockId)) {
            throw new MockServerNotFoundException("Mock server not found with ID: " + mockId);
        }
        mockEndpointRepository.deleteByMockId(mockId);
        mockRequestLogRepository.deleteByMockId(mockId);
        mockServerRepository.deleteById(mockId);
        log.info("Deleted mock server: {}", mockId);
    }

    public MockServerResponse togglePrivacy(String mockId) {
        MockServer mockServer = findOrThrow(mockId);
        mockServer.setIsPrivate(!Boolean.TRUE.equals(mockServer.getIsPrivate()));
        mockServer.setUpdatedAt(Instant.now());
        mockServerRepository.save(mockServer);
        log.info("Toggled mock server {} isPrivate to {}", mockId, mockServer.getIsPrivate());
        return mapToResponse(mockServer);
    }

    public List<MockRequestLogResponse> getServerLogs(String mockId, Integer limit, Integer offset) {
        findOrThrow(mockId);
        int pageSize   = (limit  != null && limit  > 0) ? Math.min(limit, 200) : 50;
        int pageNumber = (offset != null && offset >= 0) ? offset / pageSize : 0;
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "timestamp"));

        Page<MockRequestLog> page = mockRequestLogRepository
                .findByMockIdOrderByTimestampDesc(mockId, pageable);

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

    public MockServerResponse resetStats(String mockId) {
        MockServer mockServer = findOrThrow(mockId);
        mockServer.setRequestCount(0L);
        mockServer.setUpdatedAt(Instant.now());
        mockServerRepository.save(mockServer);
        log.info("Reset stats for mock server {}", mockId);
        return mapToResponse(mockServer);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MockServer findOrThrow(String mockId) {
        return mockServerRepository.findById(mockId)
                .orElseThrow(() -> new MockServerNotFoundException(
                        "Mock server not found with ID: " + mockId));
    }

    private String generateUniqueMockUrl() {
        String url;
        do { url = "mock-" + UUID.randomUUID().toString().substring(0, 8); }
        while (mockServerRepository.existsByMockUrl(url));
        return url;
    }

    String buildRuntimeBaseUrl(String mockUrl) {
        String base = mockServiceBaseUrl != null && !mockServiceBaseUrl.isBlank()
                ? mockServiceBaseUrl.trim() : "http://localhost:8090/mock";
        return base.replaceAll("/+$", "") + "/" + mockUrl;
    }

    MockServerResponse mapToResponse(MockServer entity) {
        return MockServerResponse.builder()
                .id(entity.getId())
                .contractId(entity.getContractId())
                .microserviceId(entity.getMicroserviceId())
                .specMetadataId(entity.getSpecMetadataId())
                .apiSpecName(entity.getApiSpecName())
                .name(entity.getName())
                .mockServiceName(entity.getMockServiceName())
                .mockUrl(entity.getMockUrl())
                .mockServerUrl(entity.getMockServerUrl())
                .responseLatencyMs(entity.getResponseLatencyMs())
                .isPrivate(entity.getIsPrivate())
                .delayMs(entity.getDelayMs())
                .requestCount(entity.getRequestCount())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
