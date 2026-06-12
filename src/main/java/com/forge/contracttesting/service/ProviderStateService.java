package com.forge.contracttesting.service;

import com.forge.contracttesting.model.ProviderState;
import com.forge.contracttesting.repository.ProviderStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderStateService {

    private final ProviderStateRepository repository;
    private final WebClient.Builder webClientBuilder;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public ProviderState save(ProviderState state) {
        if (state.getId() == null) state.setId(UUID.randomUUID().toString());
        return repository.save(state);
    }

    public List<ProviderState> listByContract(String contractId) {
        return repository.findByContractId(contractId);
    }

    public ProviderState getById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("ProviderState not found: " + id));
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    // ── State setup/teardown ──────────────────────────────────────────────────

    /**
     * POSTs to the provider's setupEndpoint to seed test data before running
     * a pact interaction. This is the standard Pact provider-states protocol.
     */
    public void setup(String baseUrl, ProviderState state) {
        if (state.getSetupEndpoint() == null || state.getSetupEndpoint().isBlank()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("state", state.getName());
        payload.put("action", "setup");
        if (state.getParams() != null) payload.put("params", state.getParams());

        try {
            webClientBuilder.baseUrl(baseUrl).build()
                    .post()
                    .uri(state.getSetupEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block(TIMEOUT);

            log.info("Provider state '{}' set up at {}{}", state.getName(), baseUrl, state.getSetupEndpoint());
        } catch (Exception e) {
            log.warn("Failed to set up provider state '{}': {}", state.getName(), e.getMessage());
        }
    }

    /**
     * POSTs to the provider's teardownEndpoint to clean up after an interaction.
     */
    public void teardown(String baseUrl, ProviderState state) {
        if (state.getTeardownEndpoint() == null || state.getTeardownEndpoint().isBlank()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("state", state.getName());
        payload.put("action", "teardown");

        try {
            webClientBuilder.baseUrl(baseUrl).build()
                    .post()
                    .uri(state.getTeardownEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block(TIMEOUT);
        } catch (Exception e) {
            log.warn("Failed to tear down provider state '{}': {}", state.getName(), e.getMessage());
        }
    }

    /**
     * Looks up a saved ProviderState by contractId + state name and runs setup.
     * If no saved state exists, skips silently (state setup is optional).
     */
    public void setupByName(String contractId, String baseUrl, String stateName) {
        if (stateName == null || stateName.isBlank()) return;
        repository.findByContractIdAndName(contractId, stateName)
                .ifPresent(state -> setup(baseUrl, state));
    }
}
