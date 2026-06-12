package com.forge.contracttesting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forge.contracttesting.model.Contract;
import com.forge.contracttesting.model.MockRequestLog;
import com.forge.contracttesting.model.PactFile;
import com.forge.contracttesting.model.PactInteraction;
import com.forge.contracttesting.repository.ContractRepository;
import com.forge.contracttesting.repository.MockRequestLogRepository;
import com.forge.contracttesting.repository.PactFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PactGeneratorService {

    private final MockRequestLogRepository logRepository;
    private final PactFileRepository pactFileRepository;
    private final ContractRepository contractRepository;
    private final ObjectMapper objectMapper;

    /**
     * Reads all MockRequestLogs for the given mockServerId + consumerName,
     * deduplicates by method+path, builds a PactFile, and saves it.
     */
    public PactFile generate(String mockServerId, String consumerName) {
        List<MockRequestLog> logs = logRepository.findByMockIdAndConsumerName(mockServerId, consumerName);

        if (logs.isEmpty()) {
            throw new IllegalArgumentException(
                    "No recorded interactions for mockServerId=" + mockServerId
                    + " consumerName=" + consumerName);
        }

        // Find the contract to get providerName
        String contractId = logs.get(0).getContractId();
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new NoSuchElementException("Contract not found: " + contractId));

        String consumerVersion = logs.stream()
                .map(MockRequestLog::getConsumerVersion)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse("1.0.0");

        // Deduplicate: one interaction per unique method+path combination
        Map<String, MockRequestLog> unique = new LinkedHashMap<>();
        for (MockRequestLog log : logs) {
            String key = log.getMethod() + " " + log.getPath();
            unique.putIfAbsent(key, log);
        }

        List<PactInteraction> interactions = new ArrayList<>();
        for (MockRequestLog log : unique.values()) {
            PactInteraction interaction = new PactInteraction();
            interaction.setDescription(log.getMethod() + " " + log.getPath());
            interaction.setProviderState("");

            interaction.setRequestMethod(log.getMethod());
            interaction.setRequestPath(log.getPath());
            interaction.setRequestHeaders(sanitiseHeaders(log.getRequestHeaders()));
            interaction.setRequestBody(log.getRequestBody());

            interaction.setResponseStatus(log.getResponseStatus());
            interaction.setResponseHeaders(sanitiseHeaders(log.getResponseHeaders()));
            // Store body shape, not exact values
            interaction.setResponseBody(extractBodyShape(log.getResponseBody()));

            interactions.add(interaction);
        }

        PactFile pactFile = new PactFile();
        pactFile.setId(UUID.randomUUID().toString());
        pactFile.setConsumerName(consumerName);
        pactFile.setConsumerVersion(consumerVersion);
        pactFile.setProviderName(contract.getProviderName() != null ? contract.getProviderName() : contract.getName());
        pactFile.setMockServerId(mockServerId);
        pactFile.setContractId(contractId);
        pactFile.setGeneratedAt(Instant.now());
        pactFile.setStatus("PENDING_VERIFICATION");
        pactFile.setInteractions(interactions);

        PactFile saved = pactFileRepository.save(pactFile);
        log.info("Generated pact file {} for consumer={} provider={} with {} interactions",
                saved.getId(), consumerName, saved.getProviderName(), interactions.size());
        return saved;
    }

    public List<PactFile> listByContract(String contractId) {
        return pactFileRepository.findByContractId(contractId);
    }

    public PactFile getById(String id) {
        return pactFileRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("PactFile not found: " + id));
    }

    /**
     * Serialises a PactFile into the standard Pact Specification v2 JSON format,
     * suitable for publishing to an external Pact Broker.
     */
    public String buildPactJson(String pactFileId) {
        PactFile pact = getById(pactFileId);

        List<Map<String, Object>> interactions = new ArrayList<>();
        for (PactInteraction i : pact.getInteractions()) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("method", i.getRequestMethod());
            req.put("path",   i.getRequestPath());
            if (i.getRequestHeaders() != null && !i.getRequestHeaders().isEmpty())
                req.put("headers", i.getRequestHeaders());
            Object requestBodyObj = i.getRequestBody();
            if (requestBodyObj != null) {
                String requestBodyStr = requestBodyObj.toString();
                if (!requestBodyStr.isBlank()) {
                    req.put("body", parseJsonOrString(requestBodyStr));
                }
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", i.getResponseStatus() == 0 ? 200 : i.getResponseStatus());
            if (i.getResponseHeaders() != null && !i.getResponseHeaders().isEmpty())
                resp.put("headers", i.getResponseHeaders());
            if (i.getResponseBody() != null && !((String) i.getResponseBody()).isBlank())
                resp.put("body", parseJsonOrString((String) i.getResponseBody()));

            Map<String, Object> interaction = new LinkedHashMap<>();
            interaction.put("description",   i.getDescription() != null ? i.getDescription() : "");
            interaction.put("providerState", i.getProviderState() != null ? i.getProviderState() : "");
            interaction.put("request",  req);
            interaction.put("response", resp);
            interactions.add(interaction);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("consumer", Map.of("name", pact.getConsumerName()));
        root.put("provider", Map.of("name", pact.getProviderName() != null ? pact.getProviderName() : "unknown"));
        root.put("interactions", interactions);
        root.put("metadata", Map.of("pactSpecification", Map.of("version", "2.0.0")));

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise pact file: " + e.getMessage(), e);
        }
    }

    private Object parseJsonOrString(String value) {
        try { return objectMapper.readTree(value); }
        catch (Exception e) { return value; }
    }

    private Map<String, String> sanitiseHeaders(Map<String, String> headers) {
        if (headers == null) return Map.of();
        // Strip internal/sensitive headers
        Map<String, String> clean = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            String lower = k.toLowerCase();
            if (!lower.equals("authorization") && !lower.equals("cookie")) {
                clean.put(k, v);
            }
        });
        return clean;
    }

    private Object extractBodyShape(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        // Return the raw body as-is; the verification engine will compare structure
        return responseBody;
    }
}
