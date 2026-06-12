package com.forge.contracttesting.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * HTTP client for fs-apispec-svc.
 * Fetches specs so the contract testing UI can populate the spec dropdown
 * and load spec content for validation.
 */
@Slf4j
@Component
public class ApiSpecClient {

    private final WebClient webClient;

    public ApiSpecClient(@Qualifier("apiSpecWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /** GET /api/v1/specs/project/{projectId} */
    public List<Map<String, Object>> getSpecsByProject(String projectId) {
        try {
            Map<String, Object> response = webClient.get()
                    .uri("/project/{projectId}", projectId)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
            return extractList(response);
        } catch (WebClientResponseException e) {
            log.error("Failed to fetch specs for project {}: {} {}", projectId, e.getStatusCode(), e.getMessage());
            return List.of();
        }
    }

    /** GET /api/v1/specs/{id} */
    public Map<String, Object> getSpecById(String specId) {
        try {
            Map<String, Object> response = webClient.get()
                    .uri("/{id}", specId)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
            return extractData(response);
        } catch (WebClientResponseException e) {
            log.error("Failed to fetch spec {}: {} {}", specId, e.getStatusCode(), e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractList(Map<String, Object> response) {
        if (response == null) return List.of();
        Object data = response.get("data");
        return data instanceof List ? (List<Map<String, Object>>) data : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(Map<String, Object> response) {
        if (response == null) return Map.of();
        Object data = response.get("data");
        return data instanceof Map ? (Map<String, Object>) data : Map.of();
    }
}
