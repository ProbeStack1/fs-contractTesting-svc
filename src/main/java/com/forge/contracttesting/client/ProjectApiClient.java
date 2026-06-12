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
 * HTTP client for fs-project-svc.
 * Fetches projects so the contract testing UI can populate the project dropdown.
 */
@Slf4j
@Component
public class ProjectApiClient {

    private final WebClient webClient;

    public ProjectApiClient(@Qualifier("projectWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /** GET /api/v1/projects/org/{orgId} */
    public List<Map<String, Object>> getProjectsByOrg(String orgId) {
        try {
            Map<String, Object> response = webClient.get()
                    .uri("/org/{orgId}", orgId)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
            return extractList(response);
        } catch (WebClientResponseException e) {
            log.error("Failed to fetch projects for org {}: {} {}", orgId, e.getStatusCode(), e.getMessage());
            return List.of();
        }
    }

    /** GET /api/v1/projects/{projectId} */
    public Map<String, Object> getProjectById(String projectId) {
        try {
            Map<String, Object> response = webClient.get()
                    .uri("/{projectId}", projectId)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
            return extractData(response);
        } catch (WebClientResponseException e) {
            log.error("Failed to fetch project {}: {} {}", projectId, e.getStatusCode(), e.getMessage());
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
