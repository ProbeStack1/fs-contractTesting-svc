package com.forge.contracttesting.controller;

import com.forge.contracttesting.dto.*;
import com.forge.contracttesting.service.MockEndpointService;
import com.forge.contracttesting.service.MockRuntimeService;
import com.forge.contracttesting.service.MockServersService;
import com.forge.contracttesting.service.SpecImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST management API for MockServer and MockEndpoint CRUD, run operations,
 * logs, and spec-based generation.
 *
 * Mock-server CRUD:   /api/v1/mocks
 * Endpoint CRUD:      /api/v1/mocks/{mockId}/endpoints
 * Run / logs / gen:   nested under the above
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mocks")
@RequiredArgsConstructor
public class MockManagementController {

    private final MockServersService mockServersService;
    private final MockEndpointService mockEndpointService;
    private final MockRuntimeService mockRuntimeService;
    private final SpecImportService specImportService;

    // ── Mock server CRUD ──────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<MockServerResponse> createMock(
            @RequestBody CreateMockServerRequest request) {
        MockServerResponse created = mockServersService.createMock(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<MockServerResponse>> listMocks(
            @RequestParam(required = false) String microserviceId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(mockServersService.listMocks(microserviceId, limit, offset));
    }

    @GetMapping("/{mockId}")
    public ResponseEntity<MockServerResponse> getMock(@PathVariable String mockId) {
        return ResponseEntity.ok(mockServersService.getMockById(mockId));
    }

    @GetMapping("/by-url/{mockUrl}")
    public ResponseEntity<MockServerResponse> getMockByUrl(@PathVariable String mockUrl) {
        return ResponseEntity.ok(mockServersService.getMockByUrl(mockUrl));
    }

    @PutMapping("/{mockId}")
    public ResponseEntity<MockServerResponse> updateMock(
            @PathVariable String mockId,
            @RequestBody UpdateMockServerRequest request) {
        return ResponseEntity.ok(mockServersService.updateMock(mockId, request));
    }

    @DeleteMapping("/{mockId}")
    public ResponseEntity<Void> deleteMock(@PathVariable String mockId) {
        mockServersService.deleteMock(mockId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{mockId}/toggle-privacy")
    public ResponseEntity<MockServerResponse> togglePrivacy(@PathVariable String mockId) {
        return ResponseEntity.ok(mockServersService.togglePrivacy(mockId));
    }

    // ── Run / generate from spec ──────────────────────────────────────────────

    @PostMapping("/{mockId}/run")
    public ResponseEntity<MockServerRunResult> runMockServer(@PathVariable String mockId) {
        return ResponseEntity.ok(mockRuntimeService.runMockServer(mockId));
    }

    @PostMapping("/generate-from-spec")
    public ResponseEntity<GenerateMockServerResponse> generateFromSpec(
            @RequestBody GenerateMockServerRequest request) {
        GenerateMockServerResponse response = specImportService.generateFromContract(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ── Endpoint CRUD ─────────────────────────────────────────────────────────

    @PostMapping("/{mockId}/endpoints")
    public ResponseEntity<EndpointResponse> createEndpoint(
            @PathVariable String mockId,
            @RequestBody CreateEndpointRequest request) {
        EndpointResponse created = mockEndpointService.createEndpoint(mockId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{mockId}/endpoints")
    public ResponseEntity<List<EndpointResponse>> listEndpoints(@PathVariable String mockId) {
        return ResponseEntity.ok(mockEndpointService.listEndpoints(mockId));
    }

    @GetMapping("/{mockId}/endpoints/{endpointId}")
    public ResponseEntity<EndpointResponse> getEndpoint(
            @PathVariable String mockId,
            @PathVariable String endpointId) {
        return ResponseEntity.ok(mockEndpointService.getEndpointById(endpointId));
    }

    @PutMapping("/{mockId}/endpoints/{endpointId}")
    public ResponseEntity<EndpointResponse> updateEndpoint(
            @PathVariable String mockId,
            @PathVariable String endpointId,
            @RequestBody UpdateEndpointRequest request) {
        return ResponseEntity.ok(mockEndpointService.updateEndpoint(endpointId, request));
    }

    @DeleteMapping("/{mockId}/endpoints/{endpointId}")
    public ResponseEntity<Void> deleteEndpoint(
            @PathVariable String mockId,
            @PathVariable String endpointId) {
        mockEndpointService.deleteEndpoint(endpointId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{mockId}/endpoints/{endpointId}/toggle")
    public ResponseEntity<EndpointResponse> toggleEndpoint(
            @PathVariable String mockId,
            @PathVariable String endpointId) {
        return ResponseEntity.ok(mockEndpointService.toggleEndpoint(endpointId));
    }

    // ── Server-level logs ─────────────────────────────────────────────────────

    /** All requests to this mock server across all endpoints, newest first. */
    @GetMapping("/{mockId}/logs")
    public ResponseEntity<List<MockRequestLogResponse>> getServerLogs(
            @PathVariable String mockId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(mockServersService.getServerLogs(mockId, limit, offset));
    }

    // ── Endpoint-level logs ───────────────────────────────────────────────────

    @GetMapping("/{mockId}/endpoints/{endpointId}/logs")
    public ResponseEntity<List<MockRequestLogResponse>> getEndpointLogs(
            @PathVariable String mockId,
            @PathVariable String endpointId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(
                mockEndpointService.getEndpointLogs(endpointId, limit, offset));
    }

    // ── Stats reset ───────────────────────────────────────────────────────────

    @PostMapping("/{mockId}/reset-stats")
    public ResponseEntity<MockServerResponse> resetStats(@PathVariable String mockId) {
        return ResponseEntity.ok(mockServersService.resetStats(mockId));
    }
}
