package com.forge.contracttesting.service;

import com.forge.contracttesting.dto.TestCaseResult;
import com.forge.contracttesting.dto.ValidationViolation;
import com.forge.contracttesting.model.ContractTest;
import com.forge.contracttesting.service.SpecParserService.EndpointInfo;
import com.forge.contracttesting.service.SpecParserService.ParsedSpec;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates an OpenAPI spec against the built-in schema rules.
 * Each ContractTest with category != "validator" is evaluated here.
 */
@Slf4j
@Service
public class SchemaValidationEngine {

    private static final Set<String> VALID_OPENAPI_TYPES =
            Set.of("string", "integer", "number", "boolean", "array", "object", "null");

    public TestCaseResult evaluate(ContractTest test, ParsedSpec spec) {
        TestCaseResult result = new TestCaseResult();
        result.setTestId(test.getId());
        result.setTestName(test.getName());
        result.setCategory(test.getCategory());
        result.setPassed(true);

        try {
            switch (test.getId()) {
                case "OA-01" -> checkSuccessResponses(result, spec);
                case "OA-02" -> checkRequiredFieldDefinitions(result, spec);
                case "OA-03" -> checkFieldTypes(result, spec);
                case "OA-04" -> checkMutatingRequestBodies(result, spec);
                case "OA-05" -> checkSecuritySchemes(result, spec);
                case "OA-06" -> checkNonDeleteSuccessResponses(result, spec);
                default -> {
                    if ("endpoint".equals(test.getCategory())) {
                        checkEndpointResponseSchema(result, test, spec);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[SchemaValidationEngine] Error evaluating test " + test.getId() + ": " + e.getMessage());
            result.addViolation(new ValidationViolation(
                    "engine", "no exception", e.getClass().getSimpleName(),
                    "Validation engine error: " + e.getMessage(), "error"
            ));
        }

        return result;
    }

    // OA-01: Every endpoint must define at least one response schema
    private void checkSuccessResponses(TestCaseResult result, ParsedSpec spec) {
        for (EndpointInfo ep : spec.getEndpoints()) {
            if (!ep.hasSuccessResponse()) {
                result.addViolation(new ValidationViolation(
                        ep.getMethod() + " " + ep.getPath(),
                        "2xx response defined",
                        "none",
                        "No success (2xx) response defined for this endpoint",
                        "error"
                ));
            }
        }
    }

    // OA-02: Required fields must exist in properties
    private void checkRequiredFieldDefinitions(TestCaseResult result, ParsedSpec spec) {
        spec.getSchemas().forEach((schemaName, schema) -> {
            List<String> required = schema.getRequired();
            Map<String, Schema> properties = schema.getProperties();
            if (required == null) return;
            for (String req : required) {
                if (properties == null || !properties.containsKey(req)) {
                    result.addViolation(new ValidationViolation(
                            schemaName + "." + req,
                            "defined property",
                            "missing",
                            "Required field \"" + req + "\" declared in required[] but not found in properties",
                            "error"
                    ));
                }
            }
        });
    }

    // OA-03: All fields must have a valid OpenAPI type, $ref, or composition keyword
    private void checkFieldTypes(TestCaseResult result, ParsedSpec spec) {
        spec.getSchemas().forEach((schemaName, schema) -> {
            Map<String, Schema> properties = schema.getProperties();
            if (properties == null) return;
            properties.forEach((fieldName, fieldSchema) -> {
                // OpenAPI 3.1 documents are parsed into Schema.types (a Set) rather than
                // the legacy singular Schema.type, since swagger-models only mirrors the
                // value into getType() when the "bind-type" system property is enabled
                // (off by default). Check both so 3.1 specs aren't flagged as untyped.
                String type = fieldSchema.getType();
                if (type == null && fieldSchema.getTypes() != null && fieldSchema.getTypes().size() == 1) {
                    type = (String) fieldSchema.getTypes().iterator().next();
                }
                boolean hasRef = fieldSchema.get$ref() != null;
                boolean hasComposition = fieldSchema.getAllOf() != null
                        || fieldSchema.getOneOf() != null
                        || fieldSchema.getAnyOf() != null;

                if (type == null && !hasRef && !hasComposition) {
                    result.addViolation(new ValidationViolation(
                            schemaName + "." + fieldName,
                            String.join(" | ", VALID_OPENAPI_TYPES),
                            "undefined",
                            "Field has no type annotation and no $ref or composition keyword",
                            "error"
                    ));
                } else if (type != null && !VALID_OPENAPI_TYPES.contains(type)) {
                    result.addViolation(new ValidationViolation(
                            schemaName + "." + fieldName,
                            String.join(" | ", VALID_OPENAPI_TYPES),
                            type,
                            "\"" + type + "\" is not a valid OpenAPI primitive type",
                            "error"
                    ));
                }
            });
        });
    }

    // OA-04: POST/PUT/PATCH endpoints must have a requestBody
    private void checkMutatingRequestBodies(TestCaseResult result, ParsedSpec spec) {
        for (EndpointInfo ep : spec.getEndpoints()) {
            if (!ep.isMutating()) continue;
            RequestBody rb = ep.getOperation().getRequestBody();
            if (rb == null) {
                result.addViolation(new ValidationViolation(
                        ep.getMethod() + " " + ep.getPath(),
                        "requestBody defined",
                        "absent",
                        "Mutating endpoint has no requestBody schema defined",
                        "error"
                ));
            }
        }
    }

    // OA-05: Security schemes referenced in operations must be declared in components
    private void checkSecuritySchemes(TestCaseResult result, ParsedSpec spec) {
        Set<String> declared = Set.copyOf(spec.getSecuritySchemes());

        for (EndpointInfo ep : spec.getEndpoints()) {
            var opSecurity = ep.getOperation().getSecurity();
            var effectiveSecurity = opSecurity != null ? opSecurity : List.of();

            for (Object reqObj : effectiveSecurity) {
                if (!(reqObj instanceof Map<?, ?> req)) continue;
                req.keySet().forEach(nameObj -> {
                    String name = nameObj != null ? nameObj.toString() : "";
                    if (!name.isBlank() && !declared.contains(name)) {
                        result.addViolation(new ValidationViolation(
                                ep.getMethod() + " " + ep.getPath(),
                                "scheme \"" + name + "\" in components/securitySchemes",
                                "undefined",
                                "Security scheme \"" + name + "\" is referenced but not declared",
                                "error"
                        ));
                    }
                });
            }
        }
    }

    // OA-06: Non-DELETE endpoints must have a 2xx response with Content-Type application/json
    private void checkNonDeleteSuccessResponses(TestCaseResult result, ParsedSpec spec) {
        for (EndpointInfo ep : spec.getEndpoints()) {
            if ("DELETE".equals(ep.getMethod())) continue;

            Map<String, ApiResponse> successes = ep.getSuccessResponses();
            if (successes.isEmpty()) {
                result.addViolation(new ValidationViolation(
                        ep.getMethod() + " " + ep.getPath(),
                        "2xx response code",
                        "none",
                        "No success response code defined",
                        "error"
                ));
                continue;
            }

            boolean hasJson = successes.values().stream().anyMatch(resp -> {
                if (resp.getContent() == null) return false;
                return resp.getContent().containsKey("application/json")
                        || resp.getContent().containsKey("*/*");
            });

            if (!hasJson) {
                result.addViolation(new ValidationViolation(
                        ep.getMethod() + " " + ep.getPath(),
                        "application/json content-type",
                        "none",
                        "No application/json content-type defined for success response",
                        "warning"
                ));
            }
        }
    }

    // For dynamically generated endpoint tests (category = "endpoint")
    private void checkEndpointResponseSchema(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        if (test.getEndpoint() == null) return;

        String[] parts = test.getEndpoint().split(" ", 2);
        if (parts.length < 2) return;
        String method = parts[0];
        String path = parts[1];

        EndpointInfo ep = spec.getEndpoints().stream()
                .filter(e -> e.getMethod().equals(method) && e.getPath().equals(path))
                .findFirst().orElse(null);

        if (ep == null) {
            result.addViolation(new ValidationViolation(
                    test.getEndpoint(),
                    "endpoint in spec",
                    "not found",
                    "Endpoint defined in contract is missing from the uploaded spec",
                    "error"
            ));
            return;
        }

        ep.getSuccessResponses().forEach((code, resp) -> {
            if (resp.getContent() == null) {
                result.addViolation(new ValidationViolation(
                        ep.getMethod() + " " + ep.getPath() + " [" + code + "]",
                        "response schema",
                        "no content",
                        "Success response " + code + " has no content defined",
                        "error"
                ));
                return;
            }
            var mediaType = resp.getContent().get("application/json");
            if (mediaType == null) mediaType = resp.getContent().get("*/*");
            if (mediaType == null || mediaType.getSchema() == null) {
                result.addViolation(new ValidationViolation(
                        ep.getMethod() + " " + ep.getPath() + " [" + code + "]",
                        "response schema",
                        "none",
                        "Success response " + code + " has no schema body",
                        "error"
                ));
            }
        });
    }
}
