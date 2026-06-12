package com.forge.contracttesting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class DeploymentGateCheckResult {
    private boolean canDeploy;
    private String providerName;
    private String providerVersion;
    private String environment;
    private List<ConsumerVerificationSummary> consumers = new ArrayList<>();
}
