package com.forge.contracttesting.controller;

import com.forge.contracttesting.dto.ApiResponse;
import com.forge.contracttesting.dto.BreakingChangeSummary;
import com.forge.contracttesting.service.ContractDiffService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/contract-diff")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", methods = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS
})
public class ContractDiffController {

    private final ContractDiffService diffService;

    /**
     * Compare two raw spec strings.
     * Body: { "contractId": "...", "specV1": "...", "specV2": "..." }
     */
    @PostMapping
    public ResponseEntity<ApiResponse<BreakingChangeSummary>> diff(
            @RequestBody Map<String, String> body) {

        String contractId = body.getOrDefault("contractId", "unknown");
        String specV1 = body.get("specV1");
        String specV2 = body.get("specV2");

        if (specV1 == null || specV1.isBlank() || specV2 == null || specV2.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("specV1 and specV2 are required"));
        }

        log.info("Diffing two raw specs for contract {}", contractId);
        return ResponseEntity.ok(ApiResponse.success(diffService.diff(contractId, specV1, specV2)));
    }

    /**
     * Diff a new spec against whatever is currently stored for this contract.
     * Useful for "would this upload break my consumers?" checks before committing.
     * Body: { "specV2": "<new raw spec>" }
     */
    @PostMapping("/{contractId}/against-stored")
    public ResponseEntity<ApiResponse<BreakingChangeSummary>> diffAgainstStored(
            @PathVariable String contractId,
            @RequestBody Map<String, String> body) {

        String specV2 = body.get("specV2");
        if (specV2 == null || specV2.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("specV2 is required"));
        }

        log.info("Diffing new spec against stored spec for contract {}", contractId);
        return ResponseEntity.ok(ApiResponse.success(
                diffService.diffWithStored(contractId, specV2)));
    }
}
