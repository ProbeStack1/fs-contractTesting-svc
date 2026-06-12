package com.forge.contracttesting.controller;

import com.forge.contracttesting.dto.ApiResponse;
import com.forge.contracttesting.dto.ContractDto;
import com.forge.contracttesting.dto.RunTestResult;
import com.forge.contracttesting.model.Consumer;
import com.forge.contracttesting.model.Contract;
import com.forge.contracttesting.model.ContractStatus;
import com.forge.contracttesting.model.ContractTest;
import com.forge.contracttesting.model.TestRunHistory;
import com.forge.contracttesting.repository.TestRunHistoryRepository;
import com.forge.contracttesting.service.ContractService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Slf4j
@RestController
@RequestMapping("/api/v1/contract-tests")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", methods = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
        RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS
})
public class ContractController {

    private final ContractService contractService;
    private final TestRunHistoryRepository historyRepository;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<ApiResponse<Contract>> create(
            @Valid @RequestBody ContractDto dto) {
        log.info("Create contract: {}", dto.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Contract created successfully", contractService.create(dto)));
    }

    /**
     * List contracts with optional filters.
     * orgId: if null/blank or starts with "admin@" → returns all contracts.
     *        otherwise → scoped to that org.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Contract>>> list(
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String providerName,
            @RequestParam(required = false) String consumerName) {
        return ResponseEntity.ok(ApiResponse.success(
                contractService.listAll(orgId, type, status, providerName, consumerName)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Contract>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(contractService.getById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Contract>> update(
            @PathVariable String id,
            @RequestBody ContractDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Contract updated", contractService.update(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        contractService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Contract deleted", null));
    }

    // ── Status transition ─────────────────────────────────────────────────────

    /** PATCH /{id}/status  body: { "status": "ACTIVE" } */
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Contract>> updateStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String raw = body.get("status");
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("status field is required"));
        }
        ContractStatus newStatus;
        try { newStatus = ContractStatus.valueOf(raw.toUpperCase()); }
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid status. Valid values: DRAFT, ACTIVE, VERIFIED, FAILED"));
        }
        return ResponseEntity.ok(ApiResponse.success("Status updated",
                contractService.updateStatus(id, newStatus)));
    }

    // ── Spec upload / download ────────────────────────────────────────────────

    /** Upload a raw spec string (YAML or JSON text). Body: { "specRaw": "...", "filename": "..." } */
    @PostMapping("/{id}/spec")
    public ResponseEntity<ApiResponse<Contract>> uploadSpec(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String raw = body.get("specRaw");
        String filename = body.getOrDefault("filename", "spec.yaml");
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("specRaw is required"));
        }
        log.info("Upload spec for contract {}: {}", id, filename);
        return ResponseEntity.ok(ApiResponse.success("Spec uploaded and parsed",
                contractService.uploadSpec(id, raw, filename)));
    }

    /** Download the raw spec that was uploaded. Returns plain text (YAML or JSON). */
    @GetMapping("/{id}/spec")
    public ResponseEntity<String> downloadSpec(@PathVariable String id) {
        String spec = contractService.getSpec(id);
        Contract c = contractService.getById(id);
        String filename = c.getSpecFilename() != null ? c.getSpecFilename() : "spec.yaml";
        MediaType mediaType = filename.endsWith(".json") ? MediaType.APPLICATION_JSON : MediaType.TEXT_PLAIN;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(spec);
    }

    // ── Validator rules upload ────────────────────────────────────────────────

    @PostMapping("/{id}/validator-rules")
    public ResponseEntity<ApiResponse<Contract>> uploadValidatorRules(
            @PathVariable String id,
            @RequestBody ValidatorRulesBody body) {
        log.info("Upload {} validator rules for contract {}", body.getRules().size(), id);
        return ResponseEntity.ok(ApiResponse.success("Validator rules saved",
                contractService.uploadValidatorRules(id, body.getRules(), body.getTests())));
    }

    // ── Run tests ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/run")
    public ResponseEntity<ApiResponse<RunTestResult>> run(@PathVariable String id) {
        log.info("Run contract tests for: {}", id);
        return ResponseEntity.ok(ApiResponse.success(contractService.runTests(id)));
    }

    // ── History ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<List<TestRunHistory>>> history(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(
                historyRepository.findTop500ByContractIdOrderByRunAtDesc(id)));
    }

    @GetMapping("/{id}/history/{historyId}")
    public ResponseEntity<ApiResponse<TestRunHistory>> getHistoryById(
            @PathVariable String id,
            @PathVariable String historyId) {
        TestRunHistory run = historyRepository.findById(historyId)
                .orElseThrow(() -> new NoSuchElementException("History record not found: " + historyId));
        if (!id.equals(run.getContractId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("History record does not belong to contract " + id));
        }
        return ResponseEntity.ok(ApiResponse.success(run));
    }

    // ── Consumer dependency map ───────────────────────────────────────────────

    /** Returns all consumers whose pacts reference this contract's provider. */
    @GetMapping("/{id}/consumers")
    public ResponseEntity<ApiResponse<List<Consumer>>> getConsumers(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(
                contractService.getConsumersForContract(id)));
    }

    // ── Inner DTO ─────────────────────────────────────────────────────────────

    public static class ValidatorRulesBody {
        private List<Map<String, Object>> rules = List.of();
        private List<ContractTest> tests = List.of();

        public List<Map<String, Object>> getRules() { return rules; }
        public void setRules(List<Map<String, Object>> rules) { this.rules = rules; }
        public List<ContractTest> getTests() { return tests; }
        public void setTests(List<ContractTest> tests) { this.tests = tests; }
    }
}
