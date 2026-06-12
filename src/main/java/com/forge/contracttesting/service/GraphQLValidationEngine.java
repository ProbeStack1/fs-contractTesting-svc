package com.forge.contracttesting.service;

import com.forge.contracttesting.dto.TestCaseResult;
import com.forge.contracttesting.dto.ValidationViolation;
import com.forge.contracttesting.model.ContractTest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates GraphQL SDL specs for type completeness, operation definitions,
 * and non-null field rules.
 */
@Slf4j
@Service
public class GraphQLValidationEngine {

    // Pattern matches: type Foo { ... }
    private static final Pattern TYPE_BLOCK = Pattern.compile(
            "(?:^|\\n)\\s*type\\s+(\\w+)\\s*\\{([^}]*)}",
            Pattern.DOTALL | Pattern.MULTILINE
    );
    // Pattern matches a field line: fieldName: SomeType
    private static final Pattern FIELD_LINE = Pattern.compile(
            "^\\s*(\\w+)\\s*(?:\\([^)]*\\))?\\s*:\\s*([\\w!\\[\\]]+)"
    );

    public TestCaseResult evaluate(ContractTest test, String specRaw) {
        TestCaseResult result = new TestCaseResult();
        result.setTestId(test.getId());
        result.setTestName(test.getName());
        result.setCategory(test.getCategory());
        result.setPassed(true);

        if (specRaw == null || specRaw.isBlank()) {
            result.addViolation(new ValidationViolation("spec", "GraphQL SDL content", "empty",
                    "No GraphQL SDL provided", "error"));
            return result;
        }

        try {
            switch (test.getId()) {
                case "GQL-01" -> checkTypeCompleteness(result, specRaw);
                case "GQL-02" -> checkQueryOperations(result, specRaw);
                case "GQL-03" -> checkMutationOperations(result, specRaw);
                default       -> checkTypeCompleteness(result, specRaw);
            }
        } catch (Exception e) {
            log.warn("GraphQL engine error on {}: {}", test.getId(), e.getMessage());
            result.addViolation(new ValidationViolation("engine", "no exception",
                    e.getClass().getSimpleName(), e.getMessage(), "error"));
        }

        return result;
    }

    private void checkTypeCompleteness(TestCaseResult result, String sdl) {
        Matcher typeMatcher = TYPE_BLOCK.matcher(sdl);
        while (typeMatcher.find()) {
            String typeName = typeMatcher.group(1);
            String body = typeMatcher.group(2);

            for (String line : body.split("\n")) {
                Matcher fieldMatcher = FIELD_LINE.matcher(line);
                if (!fieldMatcher.find()) continue;
                String fieldName = fieldMatcher.group(1);
                String fieldType = fieldMatcher.group(2);

                if (fieldType == null || fieldType.isBlank()) {
                    result.addViolation(new ValidationViolation(
                            typeName + "." + fieldName,
                            "typed field",
                            "no type",
                            "Field \"" + fieldName + "\" in type \"" + typeName + "\" has no type annotation",
                            "error"
                    ));
                }
            }
        }
    }

    private void checkQueryOperations(TestCaseResult result, String sdl) {
        if (!sdl.contains("type Query")) {
            result.addViolation(new ValidationViolation(
                    "Query", "Query type defined", "missing",
                    "No Query type found in GraphQL schema", "error"
            ));
            return;
        }
        List<String> queryFields = extractTypeFields("Query", sdl);
        if (queryFields.isEmpty()) {
            result.addViolation(new ValidationViolation(
                    "Query", "at least one operation", "empty",
                    "Query type has no operations defined", "error"
            ));
        }
    }

    private void checkMutationOperations(TestCaseResult result, String sdl) {
        if (!sdl.contains("type Mutation")) {
            // Mutation type is optional — warn rather than error
            result.addViolation(new ValidationViolation(
                    "Mutation", "Mutation type defined", "missing",
                    "No Mutation type found in GraphQL schema", "warning"
            ));
            return;
        }
        List<String> mutationFields = extractTypeFields("Mutation", sdl);
        if (mutationFields.isEmpty()) {
            result.addViolation(new ValidationViolation(
                    "Mutation", "at least one mutation", "empty",
                    "Mutation type has no operations defined", "warning"
            ));
        }
    }

    private List<String> extractTypeFields(String typeName, String sdl) {
        List<String> fields = new ArrayList<>();
        Pattern p = Pattern.compile("type\\s+" + typeName + "\\s*\\{([^}]*)}",
                Pattern.DOTALL);
        Matcher m = p.matcher(sdl);
        if (!m.find()) return fields;
        for (String line : m.group(1).split("\n")) {
            Matcher fm = FIELD_LINE.matcher(line);
            if (fm.find()) fields.add(fm.group(1));
        }
        return fields;
    }
}
