package com.forge.contracttesting.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@Document(collection = "test_run_history")
public class TestRunHistory {

    @Id
    private String id;

    private String contractId;
    private String contractName;
    private Instant runAt;
    private String specVersion;
    private int testsPassed;
    private int testsTotal;
    private boolean hasSpec;
    private List<String> skippedChecks;

    // Map of testId -> TestCaseResult
    private Map<String, TestCaseResult> results;

    private String triggeredBy;   // user/system
    private long durationMs;

    @Data
    @NoArgsConstructor
    public static class TestCaseResult {
        private boolean passed;
        private List<Map<String, String>> violations;
    }
}
