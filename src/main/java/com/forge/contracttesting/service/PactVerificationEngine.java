package com.forge.contracttesting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.contracttesting.model.Contract;
import com.forge.contracttesting.model.PactFile;
import com.forge.contracttesting.model.PactInteraction;
import com.forge.contracttesting.model.PactVerificationResult;
import com.forge.contracttesting.model.PactVerificationResult.InteractionResult;
import com.forge.contracttesting.repository.PactFileRepository;
import com.forge.contracttesting.repository.PactVerificationResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Replays each pact interaction against the real provider and verifies
 * that actual responses match the consumer-recorded expectations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PactVerificationEngine {

    private final PactFileRepository pactFileRepository;
    private final PactVerificationResultRepository resultRepository;
    private final ProviderStateService providerStateService;
    private final ObjectMapper objectMapper;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    public PactVerificationResult verify(String pactFileId, Contract contract) {
        PactFile pactFile = pactFileRepository.findById(pactFileId)
                .orElseThrow(() -> new NoSuchElementException("PactFile not found: " + pactFileId));
        return verify(pactFile, contract);
    }

    public PactVerificationResult verify(PactFile pactFile, Contract contract) {
        if (contract.getBaseUrl() == null || contract.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Contract " + contract.getId() + " has no baseUrl set");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<InteractionResult> interactionResults = new ArrayList<>();
        boolean allPassed = true;

        for (PactInteraction interaction : pactFile.getInteractions()) {
            InteractionResult ir = verifyInteraction(client, contract, pactFile, interaction);
            interactionResults.add(ir);
            if (!ir.isPassed()) allPassed = false;
        }

        PactVerificationResult pvr = new PactVerificationResult();
        pvr.setId(UUID.randomUUID().toString());
        pvr.setPactFileId(pactFile.getId());
        pvr.setContractId(contract.getId());
        pvr.setConsumerName(pactFile.getConsumerName());
        pvr.setConsumerVersion(pactFile.getConsumerVersion());
        pvr.setProviderName(pactFile.getProviderName());
        pvr.setProviderVersion(contract.getSpecVersion());
        pvr.setEnvironment(contract.getEnvironment());
        pvr.setVerifiedAt(Instant.now());
        pvr.setPassed(allPassed);
        pvr.setInteractionResults(interactionResults);

        PactVerificationResult saved = resultRepository.save(pvr);

        // Update pact file status
        pactFile.setStatus(allPassed ? "VERIFIED" : "FAILED");
        pactFileRepository.save(pactFile);

        log.info("Pact verification {} for consumer={} provider={}: {}",
                saved.getId(), pactFile.getConsumerName(), pactFile.getProviderName(),
                allPassed ? "PASSED" : "FAILED");

        return saved;
    }

    private InteractionResult verifyInteraction(HttpClient client, Contract contract,
                                                  PactFile pactFile, PactInteraction interaction) {
        InteractionResult ir = new InteractionResult();
        ir.setDescription(interaction.getDescription());
        ir.setExpectedStatus(interaction.getResponseStatus());

        // 1. Set up provider state
        providerStateService.setupByName(
                contract.getId(), contract.getBaseUrl(), interaction.getProviderState());

        // 2. Build and send the request
        try {
            String url = contract.getBaseUrl().replaceAll("/$", "") + interaction.getRequestPath();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .method(interaction.getRequestMethod(), HttpRequest.BodyPublishers.noBody());

            if (interaction.getRequestHeaders() != null) {
                interaction.getRequestHeaders().forEach(reqBuilder::header);
            }
            reqBuilder.header("Accept", "application/json");
            injectAuth(reqBuilder, contract);

            HttpResponse<String> response = client.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            ir.setActualStatus(response.statusCode());

            // 3. Compare status
            if (response.statusCode() != interaction.getResponseStatus()) {
                ir.getViolations().add(String.format("Status mismatch: expected %d, got %d",
                        interaction.getResponseStatus(), response.statusCode()));
            }

            // 4. Compare body structure
            if (interaction.getResponseBody() != null) {
                List<String> bodyViolations = compareBodyStructure(
                        interaction.getResponseBody().toString(), response.body());
                ir.getViolations().addAll(bodyViolations);
            }

            ir.setPassed(ir.getViolations().isEmpty());

        } catch (Exception e) {
            log.warn("Interaction verification failed for '{}': {}", interaction.getDescription(), e.getMessage());
            ir.getViolations().add("Request failed: " + e.getMessage());
            ir.setPassed(false);
        }

        // 5. Teardown provider state (best effort)
        providerStateService.setupByName(
                contract.getId(), contract.getBaseUrl(), interaction.getProviderState());

        return ir;
    }

    private List<String> compareBodyStructure(String expectedBody, String actualBody) {
        List<String> violations = new ArrayList<>();
        if (expectedBody == null || expectedBody.isBlank()) return violations;
        if (actualBody == null || actualBody.isBlank()) {
            violations.add("Expected a response body but got empty");
            return violations;
        }

        try {
            JsonNode expected = objectMapper.readTree(expectedBody);
            JsonNode actual = objectMapper.readTree(actualBody);
            compareNodes(expected, actual, "$", violations);
        } catch (Exception e) {
            // If expected body is not JSON, skip structural comparison
            log.debug("Body comparison skipped (not JSON): {}", e.getMessage());
        }

        return violations;
    }

    private void compareNodes(JsonNode expected, JsonNode actual, String path, List<String> violations) {
        if (expected.isObject()) {
            expected.fieldNames().forEachRemaining(field -> {
                if (!actual.has(field)) {
                    violations.add("Missing field at " + path + "." + field);
                } else {
                    JsonNode expChild = expected.get(field);
                    JsonNode actChild = actual.get(field);
                    // Type check
                    if (!expChild.getNodeType().equals(actChild.getNodeType())) {
                        violations.add(String.format("Type mismatch at %s.%s: expected %s, got %s",
                                path, field, expChild.getNodeType(), actChild.getNodeType()));
                    } else if (expChild.isObject() || expChild.isArray()) {
                        compareNodes(expChild, actChild, path + "." + field, violations);
                    }
                }
            });
        } else if (expected.isArray() && actual.isArray()) {
            if (expected.size() > 0 && actual.size() > 0) {
                compareNodes(expected.get(0), actual.get(0), path + "[0]", violations);
            }
        }
    }

    private void injectAuth(HttpRequest.Builder builder, Contract contract) {
        if (contract.getAuthType() == null || "NONE".equalsIgnoreCase(contract.getAuthType())) return;
        if (contract.getAuthValue() == null || contract.getAuthValue().isBlank()) return;
        if ("BEARER".equalsIgnoreCase(contract.getAuthType())) {
            builder.header("Authorization", "Bearer " + contract.getAuthValue());
        } else if ("API_KEY".equalsIgnoreCase(contract.getAuthType())) {
            builder.header("X-Api-Key", contract.getAuthValue());
        }
    }

    public List<PactVerificationResult> getResultsByContract(String contractId) {
        return resultRepository.findByContractId(contractId);
    }
}
