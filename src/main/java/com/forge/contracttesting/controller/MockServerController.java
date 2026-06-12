package com.forge.contracttesting.controller;

import com.forge.contracttesting.service.MockRuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Runtime catch-all — every request to /mock/{mockUrl}/** is forwarded
 * to MockRuntimeService which looks up the MockServer by slug, finds
 * the matching MockEndpoint, validates the request, and returns the
 * configured response. Consumer identity is extracted from headers.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MockServerController {

    private final MockRuntimeService mockRuntimeService;

    @RequestMapping("/mock/{mockUrl}/**")
    public ResponseEntity<Object> handleMockRequest(
            @PathVariable String mockUrl,
            HttpServletRequest request) throws IOException {

        String method   = request.getMethod();
        // getServletPath() excludes the context-path prefix (/contractTesting),
        // so the regex correctly strips /mock/{slug} and leaves only the downstream path.
        String fullPath = request.getServletPath();

        // Strip /mock/{mockUrl} prefix, keep the downstream path
        String path = fullPath.replaceFirst("/mock/[^/]+", "");
        if (path.isBlank()) path = "/";

        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        log.info("Mock runtime: {} {} (server={})", method, path, mockUrl);

        return mockRuntimeService.handleMockRequest(
                mockUrl, path, method, body.isBlank() ? null : body, request);
    }
}
