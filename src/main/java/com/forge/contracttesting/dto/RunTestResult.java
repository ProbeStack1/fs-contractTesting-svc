package com.forge.contracttesting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class RunTestResult {

    private String contractId;
    private String contractName;
    private String historyId;
    private Instant runAt;
    private long durationMs;

    private int testsTotal;
    private int testsPassed;
    private int testsFailed;
    private int totalViolations;

    private boolean hasSpec;
    private List<String> skippedChecks = new ArrayList<>();

    // keyed by testId to match the frontend shape
    private Map<String, TestCaseResult> results = new LinkedHashMap<>();

    public void addResult(TestCaseResult r) {
        results.put(r.getTestId(), r);
        testsTotal++;
        if (r.isPassed()) testsPassed++;
        else {
            testsFailed++;
            totalViolations += r.getViolations().size();
        }
    }
}
