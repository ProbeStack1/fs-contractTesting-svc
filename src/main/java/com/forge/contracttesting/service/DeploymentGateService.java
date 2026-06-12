package com.forge.contracttesting.service;

import com.forge.contracttesting.dto.ConsumerVerificationSummary;
import com.forge.contracttesting.dto.DeploymentGateCheckResult;
import com.forge.contracttesting.model.PactFile;
import com.forge.contracttesting.model.PactVerificationResult;
import com.forge.contracttesting.repository.PactFileRepository;
import com.forge.contracttesting.repository.PactVerificationResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks whether a provider version can be deployed by verifying
 * that all known consumer pacts have been verified against it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentGateService {

    private final PactFileRepository pactFileRepository;
    private final PactVerificationResultRepository verificationResultRepository;

    public DeploymentGateCheckResult canIDeploy(String providerName, String providerVersion, String environment) {
        DeploymentGateCheckResult result = new DeploymentGateCheckResult();
        result.setProviderName(providerName);
        result.setProviderVersion(providerVersion);
        result.setEnvironment(environment);

        // Load all pact files where this provider is the target
        List<PactFile> pactFiles = pactFileRepository.findByProviderName(providerName);

        if (pactFiles.isEmpty()) {
            log.info("canIDeploy {}/{} in {}: no consumers registered — allowing",
                    providerName, providerVersion, environment);
            result.setCanDeploy(true);
            return result;
        }

        // Load all verification results for this provider version + environment
        List<PactVerificationResult> verifications =
                verificationResultRepository.findByProviderNameAndProviderVersionAndEnvironment(
                        providerName, providerVersion, environment);

        List<ConsumerVerificationSummary> consumers = new ArrayList<>();
        boolean canDeploy = true;

        for (PactFile pact : pactFiles) {
            ConsumerVerificationSummary summary = new ConsumerVerificationSummary();
            summary.setConsumerName(pact.getConsumerName());
            summary.setConsumerVersion(pact.getConsumerVersion());
            summary.setPactFileId(pact.getId());

            // Find the most recent verification result for this pact
            PactVerificationResult latestVerification = verifications.stream()
                    .filter(v -> pact.getId().equals(v.getPactFileId()))
                    .reduce((first, second) -> second)  // take last
                    .orElse(null);

            if (latestVerification == null) {
                summary.setVerified(false);
                summary.setVerificationResult("NOT_RUN");
                summary.setBlockedReason(
                        "No verification has been run for consumer " + pact.getConsumerName()
                        + " against provider version " + providerVersion);
                canDeploy = false;
            } else if (latestVerification.isPassed()) {
                summary.setVerified(true);
                summary.setVerificationResult("PASSED");
            } else {
                summary.setVerified(false);
                summary.setVerificationResult("FAILED");
                summary.setBlockedReason(
                        "Consumer " + pact.getConsumerName()
                        + " pact verification FAILED against provider version " + providerVersion);
                canDeploy = false;
            }

            consumers.add(summary);
        }

        result.setCanDeploy(canDeploy);
        result.setConsumers(consumers);

        log.info("canIDeploy {}/{} in {}: {} — {} consumers checked",
                providerName, providerVersion, environment,
                canDeploy ? "ALLOWED" : "BLOCKED", consumers.size());

        return result;
    }
}
