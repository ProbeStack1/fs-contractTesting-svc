package com.forge.contracttesting.controller;

import com.forge.contracttesting.client.ApiSpecClient;
import com.forge.contracttesting.client.ProjectApiClient;
import com.forge.contracttesting.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Proxies project and spec lookups to the existing microservices.
 * The contract testing UI uses these to populate the project/spec dropdowns.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*", methods = {
        RequestMethod.GET, RequestMethod.OPTIONS
})
public class ProjectController {

    private final ProjectApiClient projectClient;
    private final ApiSpecClient apiSpecClient;

    // ── Projects ──────────────────────────────────────────────────────────────

    /** Matches frontend: ProjectsAPI.list() */
    @GetMapping("/api/v1/projects")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listProjects(
            @RequestParam(required = false) String orgId) {
        String org = orgId != null ? orgId : "default";
        return ResponseEntity.ok(ApiResponse.success(projectClient.getProjectsByOrg(org)));
    }

    @GetMapping("/api/v1/projects/{projectId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProject(
            @PathVariable String projectId) {
        return ResponseEntity.ok(ApiResponse.success(projectClient.getProjectById(projectId)));
    }

    // ── Specs ─────────────────────────────────────────────────────────────────

    /** Matches frontend: SpecsAPI.list(projectId) */
    @GetMapping("/api/v1/projects/{projectId}/specs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listSpecs(
            @PathVariable String projectId) {
        return ResponseEntity.ok(ApiResponse.success(apiSpecClient.getSpecsByProject(projectId)));
    }

    /** Matches frontend: SpecsAPI.getById(specId) */
    @GetMapping("/api/v1/specs/{specId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSpec(
            @PathVariable String specId) {
        return ResponseEntity.ok(ApiResponse.success(apiSpecClient.getSpecById(specId)));
    }
}
