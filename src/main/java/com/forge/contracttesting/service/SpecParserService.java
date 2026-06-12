package com.forge.contracttesting.service;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses OpenAPI 3.x specs from raw YAML or JSON strings.
 * Returns a normalised ParsedSpec that both validation engines consume.
 */
@Slf4j
@Service
public class SpecParserService {

    public ParsedSpec parse(String rawSpec, String filename) {
        ParseOptions opts = new ParseOptions();
        opts.setResolveFully(true);

        SwaggerParseResult result = new OpenAPIV3Parser().readContents(rawSpec, null, opts);

        if (result.getOpenAPI() == null) {
            String errors = result.getMessages() != null ? String.join("; ", result.getMessages()) : "Unknown parse error";
            throw new IllegalArgumentException("Failed to parse OpenAPI spec: " + errors);
        }

        OpenAPI openAPI = result.getOpenAPI();
        return buildParsedSpec(openAPI, rawSpec, filename);
    }

    private ParsedSpec buildParsedSpec(OpenAPI openAPI, String rawSpec, String filename) {
        ParsedSpec spec = new ParsedSpec();
        spec.setRaw(rawSpec);
        spec.setFilename(filename);
        spec.setVersion(openAPI.getInfo() != null ? openAPI.getInfo().getVersion() : "?");
        spec.setTitle(openAPI.getInfo() != null ? openAPI.getInfo().getTitle() : filename);

        // Collect endpoints
        if (openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, pathItem) ->
                extractOperations(path, pathItem, spec.getEndpoints())
            );
        }

        // Collect schemas
        if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
            openAPI.getComponents().getSchemas().forEach((name, schema) ->
                spec.getSchemas().put(name, schema)
            );
        }

        // Collect security schemes
        if (openAPI.getComponents() != null && openAPI.getComponents().getSecuritySchemes() != null) {
            spec.getSecuritySchemes().addAll(openAPI.getComponents().getSecuritySchemes().keySet());
        }

        // Top-level security requirements
        if (openAPI.getSecurity() != null) {
            openAPI.getSecurity().forEach(req -> spec.getGlobalSecurity().addAll(req.keySet()));
        }

        return spec;
    }

    private void extractOperations(String path, PathItem pathItem, List<EndpointInfo> endpoints) {
        Map<String, Operation> ops = new LinkedHashMap<>();
        if (pathItem.getGet()    != null) ops.put("GET",    pathItem.getGet());
        if (pathItem.getPost()   != null) ops.put("POST",   pathItem.getPost());
        if (pathItem.getPut()    != null) ops.put("PUT",    pathItem.getPut());
        if (pathItem.getPatch()  != null) ops.put("PATCH",  pathItem.getPatch());
        if (pathItem.getDelete() != null) ops.put("DELETE", pathItem.getDelete());
        if (pathItem.getHead()   != null) ops.put("HEAD",   pathItem.getHead());
        if (pathItem.getOptions()!= null) ops.put("OPTIONS",pathItem.getOptions());

        ops.forEach((method, op) -> {
            EndpointInfo info = new EndpointInfo();
            info.setPath(path);
            info.setMethod(method);
            info.setOperationId(op.getOperationId());
            info.setSummary(op.getSummary());
            info.setOperation(op);
            endpoints.add(info);
        });
    }

    // ── Nested result types ────────────────────────────────────────────────────

    public static class ParsedSpec {
        private String raw;
        private String filename;
        private String version;
        private String title;
        private final List<EndpointInfo> endpoints = new ArrayList<>();
        private final Map<String, Schema<?>> schemas = new LinkedHashMap<>();
        private final List<String> securitySchemes = new ArrayList<>();
        private final List<String> globalSecurity = new ArrayList<>();

        public String getRaw() { return raw; }
        public void setRaw(String raw) { this.raw = raw; }
        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<EndpointInfo> getEndpoints() { return endpoints; }
        public Map<String, Schema<?>> getSchemas() { return schemas; }
        public List<String> getSecuritySchemes() { return securitySchemes; }
        public List<String> getGlobalSecurity() { return globalSecurity; }
    }

    public static class EndpointInfo {
        private String path;
        private String method;
        private String operationId;
        private String summary;
        private Operation operation;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getOperationId() { return operationId; }
        public void setOperationId(String operationId) { this.operationId = operationId; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public Operation getOperation() { return operation; }
        public void setOperation(Operation operation) { this.operation = operation; }

        public boolean isMutating() {
            return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        }

        public boolean hasSuccessResponse() {
            if (operation == null || operation.getResponses() == null) return false;
            return operation.getResponses().keySet().stream().anyMatch(c -> c.startsWith("2"));
        }

        public Map<String, ApiResponse> getSuccessResponses() {
            Map<String, ApiResponse> out = new LinkedHashMap<>();
            if (operation == null || operation.getResponses() == null) return out;
            operation.getResponses().forEach((code, resp) -> {
                if (code.startsWith("2")) out.put(code, resp);
            });
            return out;
        }
    }
}
