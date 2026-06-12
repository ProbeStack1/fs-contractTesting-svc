package com.forge.contracttesting.service;

import com.forge.contracttesting.dto.TestCaseResult;
import com.forge.contracttesting.dto.ValidationViolation;
import com.forge.contracttesting.model.ContractTest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates gRPC .proto files for field number uniqueness, message completeness,
 * and RPC method definitions.
 */
@Slf4j
@Service
public class GrpcValidationEngine {

    private static final Pattern MESSAGE_BLOCK = Pattern.compile(
            "message\\s+(\\w+)\\s*\\{([^}]*)}",
            Pattern.DOTALL
    );
    private static final Pattern FIELD_LINE = Pattern.compile(
            "^\\s*(?:repeated\\s+)?(\\w+)\\s+(\\w+)\\s*=\\s*(\\d+)"
    );
    private static final Pattern SERVICE_BLOCK = Pattern.compile(
            "service\\s+(\\w+)\\s*\\{([^}]*)}",
            Pattern.DOTALL
    );
    private static final Pattern RPC_LINE = Pattern.compile(
            "rpc\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)\\s*returns\\s*\\(\\s*(\\w+)\\s*\\)"
    );

    public TestCaseResult evaluate(ContractTest test, String specRaw) {
        TestCaseResult result = new TestCaseResult();
        result.setTestId(test.getId());
        result.setTestName(test.getName());
        result.setCategory(test.getCategory());
        result.setPassed(true);

        if (specRaw == null || specRaw.isBlank()) {
            result.addViolation(new ValidationViolation("spec", "proto file content", "empty",
                    "No .proto file content provided", "error"));
            return result;
        }

        try {
            switch (test.getId()) {
                case "GRPC-01" -> checkServiceCompleteness(result, specRaw);
                case "GRPC-02" -> checkMessageFields(result, specRaw);
                case "GRPC-03" -> checkRpcTypes(result, specRaw);
                default        -> checkServiceCompleteness(result, specRaw);
            }
        } catch (Exception e) {
            log.warn("gRPC engine error on {}: {}", test.getId(), e.getMessage());
            result.addViolation(new ValidationViolation("engine", "no exception",
                    e.getClass().getSimpleName(), e.getMessage(), "error"));
        }

        return result;
    }

    private void checkServiceCompleteness(TestCaseResult result, String proto) {
        Matcher serviceMatcher = SERVICE_BLOCK.matcher(proto);
        boolean foundService = false;
        while (serviceMatcher.find()) {
            foundService = true;
            String serviceName = serviceMatcher.group(1);
            String body = serviceMatcher.group(2);
            Matcher rpcMatcher = RPC_LINE.matcher(body);
            if (!rpcMatcher.find()) {
                result.addViolation(new ValidationViolation(
                        serviceName, "at least one RPC method", "empty",
                        "Service \"" + serviceName + "\" has no RPC methods defined", "error"
                ));
            }
        }
        if (!foundService) {
            result.addViolation(new ValidationViolation(
                    "service", "at least one service", "none",
                    "No service definition found in .proto file", "error"
            ));
        }
    }

    private void checkMessageFields(TestCaseResult result, String proto) {
        Matcher msgMatcher = MESSAGE_BLOCK.matcher(proto);
        while (msgMatcher.find()) {
            String msgName = msgMatcher.group(1);
            String body = msgMatcher.group(2);

            // Check for duplicate field numbers
            Set<Integer> fieldNumbers = new HashSet<>();
            for (String line : body.split("\n")) {
                Matcher fieldMatcher = FIELD_LINE.matcher(line);
                if (!fieldMatcher.find()) continue;
                int fieldNumber = Integer.parseInt(fieldMatcher.group(3));
                if (!fieldNumbers.add(fieldNumber)) {
                    result.addViolation(new ValidationViolation(
                            msgName + " field " + fieldNumber,
                            "unique field number",
                            "duplicate",
                            "Field number " + fieldNumber + " is reused in message \"" + msgName + "\"",
                            "error"
                    ));
                }
            }
        }
    }

    private void checkRpcTypes(TestCaseResult result, String proto) {
        // Collect all message names
        Set<String> messageNames = new HashSet<>();
        Matcher msgMatcher = MESSAGE_BLOCK.matcher(proto);
        while (msgMatcher.find()) messageNames.add(msgMatcher.group(1));

        // Check each RPC's request and response types are defined messages
        Matcher serviceMatcher = SERVICE_BLOCK.matcher(proto);
        while (serviceMatcher.find()) {
            String serviceName = serviceMatcher.group(1);
            String body = serviceMatcher.group(2);
            Matcher rpcMatcher = RPC_LINE.matcher(body);
            while (rpcMatcher.find()) {
                String rpcName = rpcMatcher.group(1);
                String requestType = rpcMatcher.group(2);
                String responseType = rpcMatcher.group(3);

                if (!messageNames.contains(requestType)) {
                    result.addViolation(new ValidationViolation(
                            serviceName + "." + rpcName,
                            "defined message type " + requestType,
                            "undefined",
                            "RPC request type \"" + requestType + "\" is not defined as a message",
                            "error"
                    ));
                }
                if (!messageNames.contains(responseType)) {
                    result.addViolation(new ValidationViolation(
                            serviceName + "." + rpcName,
                            "defined message type " + responseType,
                            "undefined",
                            "RPC response type \"" + responseType + "\" is not defined as a message",
                            "error"
                    ));
                }
            }
        }
    }
}
