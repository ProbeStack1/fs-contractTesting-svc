package com.forge.contracttesting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class BreakingChangeSummary {
    private String contractId;
    private boolean hasBreakingChanges;
    private List<BreakingChange> breakingChanges = new ArrayList<>();
    private List<BreakingChange> nonBreakingChanges = new ArrayList<>();
}
