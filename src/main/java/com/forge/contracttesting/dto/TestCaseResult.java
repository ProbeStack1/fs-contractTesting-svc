package com.forge.contracttesting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TestCaseResult {
    private String testId;
    private String testName;
    private String category;
    private boolean passed;
    private List<ValidationViolation> violations = new ArrayList<>();

    public void addViolation(ValidationViolation v) {
        this.violations.add(v);
        this.passed = false;
    }
}
