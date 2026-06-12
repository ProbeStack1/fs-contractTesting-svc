package com.forge.contracttesting.service;

import com.forge.contracttesting.dto.RunTestResult;
import com.forge.contracttesting.dto.TestCaseResult;
import com.forge.contracttesting.dto.ValidationViolation;
import com.forge.contracttesting.model.Contract;
import com.forge.contracttesting.model.ContractTest;
import com.forge.contracttesting.model.TestRunHistory;
import com.forge.contracttesting.repository.TestRunHistoryRepository;
import com.forge.contracttesting.service.SpecParserService.ParsedSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the full contract test execution:
 *   1. Load spec
 *   2. Parse spec
 *   3. Schema validation
 *   4. Enterprise / company-rule validation
 *   5. Generate report
 *   6. Persist history
 *   7. Return result
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractTestRunner {

    private final SpecParserService specParserService;
    private final SchemaValidationEngine schemaEngine;
    private final EnterpriseValidationEngine enterpriseEngine;
    private final GraphQLValidationEngine graphqlEngine;
    private final GrpcValidationEngine grpcEngine;
    private final AsyncApiValidationEngine asyncEngine;
    private final HttpValidationEngine httpEngine;
    private final TestRunHistoryRepository historyRepository;

    public RunTestResult run(Contract contract) {
        long startMs = System.currentTimeMillis();
        Instant runAt = Instant.now();

        RunTestResult report = new RunTestResult();
        report.setContractId(contract.getId());
        report.setContractName(contract.getName());
        report.setRunAt(runAt);
        report.setHasSpec(contract.getSpecRaw() != null && !contract.getSpecRaw().isBlank());

        List<String> skipped = new ArrayList<>();
        ParsedSpec parsedSpec = null;

        // ── Step 1 & 2: Parse spec ─────────────────────────────────────────────
        if (report.isHasSpec()) {
            try {
                parsedSpec = specParserService.parse(contract.getSpecRaw(), contract.getSpecFilename());
                log.info("Parsed spec for contract {}: {} endpoints, {} schemas",
                        contract.getId(),
                        parsedSpec.getEndpoints().size(),
                        parsedSpec.getSchemas().size());
            } catch (Exception e) {
                log.error("Spec parse failed for contract {}: {}", contract.getId(), e.getMessage());
                // Return a single error result rather than crashing
                TestCaseResult parseError = new TestCaseResult();
                parseError.setTestId("PARSE-ERR");
                parseError.setTestName("Spec Parsing");
                parseError.setCategory("system");
                parseError.addViolation(new ValidationViolation(
                        "spec", "valid OpenAPI 3.x document", "parse failure",
                        "Could not parse the specification: " + e.getMessage(), "error"
                ));
                report.addResult(parseError);
                report.setDurationMs(System.currentTimeMillis() - startMs);
                return report;
            }
        } else {
            skipped.add("Schema validation");
            skipped.add("Required fields");
            skipped.add("Type checks");
            skipped.add("Content-Type checks");
            skipped.add("Security scheme checks");
        }

        if (contract.getValidatorRules() == null || contract.getValidatorRules().isEmpty()) {
            skipped.add("Company validator rules");
        }

        report.setSkippedChecks(skipped);

        // ── Step 3 & 4: Evaluate each test ────────────────────────────────────
        List<ContractTest> tests = contract.getTests();
        if (tests == null || tests.isEmpty()) {
            tests = List.of();
            log.warn("Contract {} has no tests defined", contract.getId());
        }

        for (ContractTest test : tests) {
            TestCaseResult result;

            if (parsedSpec == null && !"graphql".equals(contract.getType())
                    && !"grpc".equals(contract.getType())
                    && !"async".equals(contract.getType())) {
                result = noSpecResult(test);
            } else if ("validator".equals(test.getCategory())) {
                result = enterpriseEngine.evaluate(test, parsedSpec);
            } else if ("graphql".equals(contract.getType())) {
                result = graphqlEngine.evaluate(test, contract.getSpecRaw());
            } else if ("grpc".equals(contract.getType())) {
                result = grpcEngine.evaluate(test, contract.getSpecRaw());
            } else if ("async".equals(contract.getType())) {
                result = asyncEngine.evaluate(test, contract.getSpecRaw());
            } else {
                result = schemaEngine.evaluate(test, parsedSpec);
            }

            report.addResult(result);
        }

        // ── Live HTTP validation (runs when baseUrl is configured)
        if (parsedSpec != null && contract.getBaseUrl() != null && !contract.getBaseUrl().isBlank()) {
            List<TestCaseResult> httpResults = httpEngine.validateLive(contract, parsedSpec);
            httpResults.forEach(report::addResult);
        }

        // ── Step 5: Finalize timing ────────────────────────────────────────────
        report.setDurationMs(System.currentTimeMillis() - startMs);

        // ── Step 6: Persist history ────────────────────────────────────────────
        String historyId = persistHistory(contract, report, runAt);
        report.setHistoryId(historyId);

        log.info("Contract run complete for {}: {}/{} passed in {}ms",
                contract.getId(), report.getTestsPassed(), report.getTestsTotal(), report.getDurationMs());

        return report;
    }

    private TestCaseResult noSpecResult(ContractTest test) {
        TestCaseResult result = new TestCaseResult();
        result.setTestId(test.getId());
        result.setTestName(test.getName());
        result.setCategory(test.getCategory());
        result.addViolation(new ValidationViolation(
                "spec", "uploaded specification", "none",
                "Upload a spec file to enable real contract validation", "error"
        ));
        return result;
    }

    private String persistHistory(Contract contract, RunTestResult report, Instant runAt) {
        try {
            TestRunHistory history = new TestRunHistory();
            history.setId(UUID.randomUUID().toString());
            history.setContractId(contract.getId());
            history.setContractName(contract.getName());
            history.setRunAt(runAt);
            history.setSpecVersion(contract.getSpecVersion() != null ? contract.getSpecVersion() : "?");
            history.setTestsPassed(report.getTestsPassed());
            history.setTestsTotal(report.getTestsTotal());
            history.setHasSpec(report.isHasSpec());
            history.setSkippedChecks(report.getSkippedChecks());
            history.setDurationMs(report.getDurationMs());

            // Convert results to history format
            Map<String, TestRunHistory.TestCaseResult> histResults = new LinkedHashMap<>();
            report.getResults().forEach((testId, tcr) -> {
                TestRunHistory.TestCaseResult hr = new TestRunHistory.TestCaseResult();
                hr.setPassed(tcr.isPassed());
                hr.setViolations(tcr.getViolations().stream()
                        .map(v -> Map.of(
                                "field", v.getField() != null ? v.getField() : "",
                                "expectedType", v.getExpectedType() != null ? v.getExpectedType() : "",
                                "actualType", v.getActualType() != null ? v.getActualType() : "",
                                "reason", v.getReason() != null ? v.getReason() : ""
                        ))
                        .toList());
                histResults.put(testId, hr);
            });
            history.setResults(histResults);

            TestRunHistory saved = historyRepository.save(history);
            return saved.getId();
        } catch (Exception e) {
            log.error("Failed to save test run history for contract {}: {}", contract.getId(), e.getMessage());
            return null;
        }
    }
}
