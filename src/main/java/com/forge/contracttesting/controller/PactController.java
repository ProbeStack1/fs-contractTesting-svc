package com.forge.contracttesting.controller;

import com.forge.contracttesting.dto.ApiResponse;
import com.forge.contracttesting.dto.DeploymentGateCheckResult;
import com.forge.contracttesting.model.Contract;
import com.forge.contracttesting.model.PactFile;
import com.forge.contracttesting.model.PactVerificationResult;
import com.forge.contracttesting.model.ProviderState;
import com.forge.contracttesting.repository.ContractRepository;
import com.forge.contracttesting.service.DeploymentGateService;
import com.forge.contracttesting.service.PactGeneratorService;
import com.forge.contracttesting.service.PactVerificationEngine;
import com.forge.contracttesting.service.ProviderStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@RestController
@RequestMapping("/api/v1/pact")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", methods = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
        RequestMethod.DELETE, RequestMethod.OPTIONS
})
public class PactController {

    private final PactGeneratorService pactGeneratorService;
    private final PactVerificationEngine pactVerificationEngine;
    private final DeploymentGateService deploymentGateService;
    private final ProviderStateService providerStateService;
    private final ContractRepository contractRepository;

    // ── Pact generation ───────────────────────────────────────────────────────

    /**
     * Generate a pact file from recorded mock interactions.
     * Body: { "mockServerId": "...", "consumerName": "order-service" }
     */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<PactFile>> generate(@RequestBody Map<String, String> body) {
        String mockServerId = body.get("mockServerId");
        String consumerName = body.get("consumerName");
        if (mockServerId == null || consumerName == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("mockServerId and consumerName are required"));
        }
        log.info("Generating pact for mockServerId={} consumer={}", mockServerId, consumerName);
        PactFile pact = pactGeneratorService.generate(mockServerId, consumerName);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Pact file generated", pact));
    }

    /** List all pact files for a contract. */
    @GetMapping("/contract/{contractId}")
    public ResponseEntity<ApiResponse<List<PactFile>>> listByContract(
            @PathVariable String contractId) {
        return ResponseEntity.ok(ApiResponse.success(
                pactGeneratorService.listByContract(contractId)));
    }

    /** Get a single pact file by id. */
    @GetMapping("/{pactFileId}")
    public ResponseEntity<ApiResponse<PactFile>> getById(@PathVariable String pactFileId) {
        return ResponseEntity.ok(ApiResponse.success(
                pactGeneratorService.getById(pactFileId)));
    }

    /**
     * Download the pact file in standard Pact Specification v2 JSON format,
     * ready to be published to an external Pact Broker.
     */
    @GetMapping("/{pactFileId}/download")
    public ResponseEntity<String> download(@PathVariable String pactFileId) {
        PactFile pact = pactGeneratorService.getById(pactFileId);
        String json = pactGeneratorService.buildPactJson(pactFileId);
        String filename = pact.getConsumerName() + "-" + pact.getProviderName() + ".json";
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(json);
    }

    // ── Pact verification ─────────────────────────────────────────────────────

    /**
     * Replay a pact against the real provider and save the verification result.
     * Body: { "contractId": "..." }
     */
    @PostMapping("/{pactFileId}/verify")
    public ResponseEntity<ApiResponse<PactVerificationResult>> verify(
            @PathVariable String pactFileId,
            @RequestBody Map<String, String> body) {

        String contractId = body.get("contractId");
        if (contractId == null || contractId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("contractId is required"));
        }

        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new NoSuchElementException("Contract not found: " + contractId));

        log.info("Verifying pact {} against contract {}", pactFileId, contractId);
        PactVerificationResult result = pactVerificationEngine.verify(pactFileId, contract);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** List all verification results for a contract. */
    @GetMapping("/results/contract/{contractId}")
    public ResponseEntity<ApiResponse<List<PactVerificationResult>>> getResults(
            @PathVariable String contractId) {
        return ResponseEntity.ok(ApiResponse.success(
                pactVerificationEngine.getResultsByContract(contractId)));
    }

    // ── Can-I-Deploy ──────────────────────────────────────────────────────────

    /**
     * Check whether a provider version can be deployed.
     * GET /api/v1/pact/can-i-deploy?provider=user-service&version=2.1.0&env=staging
     */
    @GetMapping("/can-i-deploy")
    public ResponseEntity<ApiResponse<DeploymentGateCheckResult>> canIDeploy(
            @RequestParam String provider,
            @RequestParam String version,
            @RequestParam(defaultValue = "staging") String env) {

        log.info("canIDeploy check: provider={} version={} env={}", provider, version, env);
        DeploymentGateCheckResult result = deploymentGateService.canIDeploy(provider, version, env);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── Provider states ───────────────────────────────────────────────────────

    @PostMapping("/provider-states")
    public ResponseEntity<ApiResponse<ProviderState>> createProviderState(
            @RequestBody ProviderState state) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Provider state saved",
                        providerStateService.save(state)));
    }

    @GetMapping("/provider-states/contract/{contractId}")
    public ResponseEntity<ApiResponse<List<ProviderState>>> listProviderStates(
            @PathVariable String contractId) {
        return ResponseEntity.ok(ApiResponse.success(
                providerStateService.listByContract(contractId)));
    }

    @DeleteMapping("/provider-states/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteProviderState(@PathVariable String id) {
        providerStateService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Provider state deleted", null));
    }
}
