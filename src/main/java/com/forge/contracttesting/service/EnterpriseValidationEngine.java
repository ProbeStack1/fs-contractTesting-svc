package com.forge.contracttesting.service;

import com.forge.contracttesting.dto.TestCaseResult;
import com.forge.contracttesting.dto.ValidationViolation;
import com.forge.contracttesting.model.ContractTest;
import com.forge.contracttesting.service.SpecParserService.ParsedSpec;
import io.swagger.v3.oas.models.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates a parsed spec against enterprise / company-standard rules.
 * Each validator-category ContractTest carries the rule metadata
 * (field, ruleType, assertion) extracted from the uploaded rules file.
 */
@Slf4j
@Service
public class EnterpriseValidationEngine {

    private static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

    public TestCaseResult evaluate(ContractTest test, ParsedSpec spec) {
        TestCaseResult result = new TestCaseResult();
        result.setTestId(test.getId());
        result.setTestName(test.getName());
        result.setCategory(test.getCategory());
        result.setPassed(true);

        try {
            switch (test.getRuleType() != null ? test.getRuleType() : "") {
                case "required"  -> validateRequired(result, test, spec);
                case "type"      -> validateType(result, test, spec);
                case "format"    -> validateFormat(result, test, spec);
                case "enum"      -> validateEnum(result, test, spec);
                case "range"     -> validateRange(result, test, spec);
                case "length"    -> validateLength(result, test, spec);
                case "pattern"   -> validatePattern(result, test, spec);
                case "naming"    -> validateNaming(result, test, spec);
                case "mandatory" -> validateMandatoryField(result, test, spec);
                case "spectral"  -> validateSpectralRule(result, test, spec);
                default          -> validateGeneric(result, test, spec);
            }
        } catch (Exception e) {
            log.warn("Enterprise engine error on test {}: {}", test.getId(), e.getMessage());
            result.addViolation(new ValidationViolation(
                    "engine", "no exception", e.getClass().getSimpleName(),
                    "Validation engine error: " + e.getMessage(), "error"
            ));
        }

        return result;
    }

    // ── Rule type handlers ─────────────────────────────────────────────────────

    private void validateRequired(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        FieldLookup lookup = resolveField(test.getField(), spec);
        if (lookup == null) {
            result.addViolation(new ValidationViolation(
                    test.getField(), "defined field", "not in spec",
                    "No schema or property matching \"" + test.getField() + "\" found in the spec",
                    "error"
            ));
            return;
        }
        List<String> required = lookup.schema().getRequired();
        if (required == null || !required.contains(lookup.fieldName())) {
            result.addViolation(new ValidationViolation(
                    test.getField(), "required", "optional",
                    "\"" + lookup.fieldName() + "\" must be required per company standards but is not in the required[] array",
                    "error"
            ));
        }
    }

    private void validateType(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        FieldLookup lookup = resolveField(test.getField(), spec);
        if (lookup == null) { fieldNotFound(result, test.getField()); return; }

        Schema<?> prop = lookup.property();
        if (prop == null) { fieldNotFound(result, test.getField()); return; }

        // Prefer typed field; fall back to assertion string parsing
        String expectedType = test.getExpectedType() != null
                ? test.getExpectedType()
                : extractTypeFromAssertion(test.getAssertion());
        if (expectedType == null) return;

        String actualType = prop.getType();
        if (!expectedType.equals(actualType)) {
            result.addViolation(new ValidationViolation(
                    test.getField(), expectedType, actualType != null ? actualType : "undefined",
                    "Type mismatch for field \"" + lookup.fieldName() + "\"",
                    "error"
            ));
        }
    }

    private void validateFormat(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        FieldLookup lookup = resolveField(test.getField(), spec);
        if (lookup == null) { fieldNotFound(result, test.getField()); return; }

        Schema<?> prop = lookup.property();
        if (prop == null) { fieldNotFound(result, test.getField()); return; }

        if (prop.getFormat() == null || prop.getFormat().isBlank()) {
            result.addViolation(new ValidationViolation(
                    test.getField(), "format annotation", "none",
                    "\"" + lookup.fieldName() + "\" must have a format annotation per company standards",
                    "warning"
            ));
        }
    }

    private void validateEnum(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        FieldLookup lookup = resolveField(test.getField(), spec);
        if (lookup == null) { fieldNotFound(result, test.getField()); return; }

        Schema<?> prop = lookup.property();
        if (prop == null) { fieldNotFound(result, test.getField()); return; }

        if (prop.getEnum() == null || prop.getEnum().isEmpty()) {
            result.addViolation(new ValidationViolation(
                    test.getField(), "enum values defined", "no enum",
                    "\"" + lookup.fieldName() + "\" must have enum values per company standards but none are defined",
                    "error"
            ));
        }
    }

    private void validateRange(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        FieldLookup lookup = resolveField(test.getField(), spec);
        if (lookup == null) { fieldNotFound(result, test.getField()); return; }

        Schema<?> prop = lookup.property();
        if (prop == null) { fieldNotFound(result, test.getField()); return; }

        // Use typed fields when available; fall back to assertion string parsing
        if (test.getMinimumValue() != null) {
            double expected = test.getMinimumValue();
            if (prop.getMinimum() == null || prop.getMinimum().doubleValue() < expected) {
                result.addViolation(new ValidationViolation(
                        test.getField(), ">= " + expected,
                        prop.getMinimum() != null ? ">= " + prop.getMinimum() : "no minimum",
                        "Minimum constraint missing or below required threshold", "error"
                ));
            }
        } else if (test.getAssertion() != null && test.getAssertion().contains(">=")) {
            try {
                double expected = Double.parseDouble(test.getAssertion().replaceAll(".*>=\\s*([\\d.]+).*", "$1"));
                if (prop.getMinimum() == null || prop.getMinimum().doubleValue() < expected) {
                    result.addViolation(new ValidationViolation(
                            test.getField(), ">= " + expected,
                            prop.getMinimum() != null ? ">= " + prop.getMinimum() : "no minimum",
                            "Minimum constraint missing or below required threshold", "error"
                    ));
                }
            } catch (NumberFormatException ignored) {}
        }

        if (test.getMaximumValue() != null) {
            double expected = test.getMaximumValue();
            if (prop.getMaximum() == null || prop.getMaximum().doubleValue() > expected) {
                result.addViolation(new ValidationViolation(
                        test.getField(), "<= " + expected,
                        prop.getMaximum() != null ? "<= " + prop.getMaximum() : "no maximum",
                        "Maximum constraint missing or above required threshold", "error"
                ));
            }
        } else if (test.getAssertion() != null && test.getAssertion().contains("<=")) {
            try {
                double expected = Double.parseDouble(test.getAssertion().replaceAll(".*<=\\s*([\\d.]+).*", "$1"));
                if (prop.getMaximum() == null || prop.getMaximum().doubleValue() > expected) {
                    result.addViolation(new ValidationViolation(
                            test.getField(), "<= " + expected,
                            prop.getMaximum() != null ? "<= " + prop.getMaximum() : "no maximum",
                            "Maximum constraint missing or above required threshold", "error"
                    ));
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private void validateLength(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        FieldLookup lookup = resolveField(test.getField(), spec);
        if (lookup == null) { fieldNotFound(result, test.getField()); return; }

        Schema<?> prop = lookup.property();
        if (prop == null) { fieldNotFound(result, test.getField()); return; }

        // Use typed fields when available; fall back to assertion string
        boolean checkMin = test.getMinLength() != null
                || (test.getAssertion() != null && test.getAssertion().contains("minLength"));
        boolean checkMax = test.getMaxLength() != null
                || (test.getAssertion() != null && test.getAssertion().contains("maxLength"));

        if (checkMin && prop.getMinLength() == null) {
            result.addViolation(new ValidationViolation(
                    test.getField(), "minLength defined", "none",
                    "minLength constraint required by company standards but not defined", "warning"
            ));
        }
        if (checkMax && prop.getMaxLength() == null) {
            result.addViolation(new ValidationViolation(
                    test.getField(), "maxLength defined", "none",
                    "maxLength constraint required by company standards but not defined", "warning"
            ));
        }
    }

    private void validatePattern(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        FieldLookup lookup = resolveField(test.getField(), spec);
        if (lookup == null) { fieldNotFound(result, test.getField()); return; }

        Schema<?> prop = lookup.property();
        if (prop == null) { fieldNotFound(result, test.getField()); return; }

        if (prop.getPattern() == null || prop.getPattern().isBlank()) {
            result.addViolation(new ValidationViolation(
                    test.getField(), "pattern defined", "none",
                    "\"" + lookup.fieldName() + "\" must have a regex pattern per company standards",
                    "error"
            ));
        }
    }

    private void validateNaming(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        // Validates that all property names in all schemas use camelCase
        spec.getSchemas().forEach((schemaName, schema) -> {
            Map<String, Schema> properties = schema.getProperties();
            if (properties == null) return;
            properties.keySet().forEach(fieldName -> {
                if (!CAMEL_CASE.matcher(fieldName).matches()) {
                    result.addViolation(new ValidationViolation(
                            schemaName + "." + fieldName,
                            "camelCase name",
                            fieldName,
                            "Field name \"" + fieldName + "\" does not follow camelCase naming convention",
                            "warning"
                    ));
                }
            });
        });
    }

    private void validateMandatoryField(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        // Checks that a mandatory field exists in at least one response schema
        String fieldName = test.getField();
        if (fieldName == null || fieldName.isBlank() || "—".equals(fieldName)) return;

        boolean found = spec.getSchemas().values().stream()
                .anyMatch(schema -> schema.getProperties() != null
                        && schema.getProperties().containsKey(fieldName));

        if (!found) {
            result.addViolation(new ValidationViolation(
                    fieldName, "present in response schema", "not found",
                    "Mandatory field \"" + fieldName + "\" is not present in any response schema",
                    "error"
            ));
        }
    }

    private void validateSpectralRule(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        // Spectral-style rules are evaluated as naming/mandatory checks based on their given path
        String given = test.getField();
        if (given == null || given.isBlank() || "—".equals(given)) return;

        if (given.contains("paths")) {
            // Path-level rule — check all endpoints have operationId
            boolean allHaveOperationId = spec.getEndpoints().stream()
                    .allMatch(ep -> ep.getOperationId() != null && !ep.getOperationId().isBlank());
            if (!allHaveOperationId) {
                result.addViolation(new ValidationViolation(
                        given, "operationId on all operations", "missing",
                        "One or more operations are missing an operationId (required by company Spectral ruleset)",
                        "error"
                ));
            }
        }
    }

    private void validateGeneric(TestCaseResult result, ContractTest test, ParsedSpec spec) {
        // For unknown rule types, attempt field-exists check
        if (test.getField() != null && !test.getField().isBlank() && !"—".equals(test.getField())) {
            FieldLookup lookup = resolveField(test.getField(), spec);
            if (lookup == null) {
                result.addViolation(new ValidationViolation(
                        test.getField(), "defined field", "not in spec",
                        "Field \"" + test.getField() + "\" referenced in company rules but not found in spec",
                        "warning"
                ));
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private record FieldLookup(Schema<?> schema, String schemaName, String fieldName, Schema<?> property) {}

    private FieldLookup resolveField(String fieldPath, ParsedSpec spec) {
        if (fieldPath == null || fieldPath.isBlank() || "—".equals(fieldPath)) return null;

        String[] parts = fieldPath.split("\\.", 2);
        String schemaName = parts[0];
        String fieldName = parts.length > 1 ? parts[1] : null;

        Schema<?> schema = spec.getSchemas().get(schemaName);

        if (schema == null) {
            // Try treating the whole path as a field name in any schema
            for (Map.Entry<String, Schema<?>> entry : spec.getSchemas().entrySet()) {
                Map<String, Schema> props = entry.getValue().getProperties();
                if (props != null && props.containsKey(fieldPath)) {
                    return new FieldLookup(entry.getValue(), entry.getKey(), fieldPath,
                            (Schema<?>) props.get(fieldPath));
                }
            }
            return null;
        }

        if (fieldName == null) {
            return new FieldLookup(schema, schemaName, schemaName, null);
        }

        Map<String, Schema> props = schema.getProperties();
        Schema<?> prop = props != null ? (Schema<?>) props.get(fieldName) : null;
        return new FieldLookup(schema, schemaName, fieldName, prop);
    }

    private String extractTypeFromAssertion(String assertion) {
        if (assertion == null) return null;
        var m = Pattern.compile("typeof\\s+(\\w+)").matcher(assertion);
        return m.find() ? m.group(1) : null;
    }

    private void fieldNotFound(TestCaseResult result, String field) {
        result.addViolation(new ValidationViolation(
                field, "field present in spec", "missing",
                "Field \"" + field + "\" not found in spec schemas",
                "error"
        ));
    }
}
