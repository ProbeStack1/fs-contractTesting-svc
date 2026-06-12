package com.forge.contracttesting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.forge.contracttesting.dto.TestCaseResult;
import com.forge.contracttesting.dto.ValidationViolation;
import com.forge.contracttesting.model.ContractTest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;

/**
 * Validates AsyncAPI specs for channel schema completeness, subscribe/publish
 * operations, and typed message payload properties.
 */
@Slf4j
@Service
public class AsyncApiValidationEngine {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public TestCaseResult evaluate(ContractTest test, String specRaw) {
        TestCaseResult result = new TestCaseResult();
        result.setTestId(test.getId());
        result.setTestName(test.getName());
        result.setCategory(test.getCategory());
        result.setPassed(true);

        if (specRaw == null || specRaw.isBlank()) {
            result.addViolation(new ValidationViolation("spec", "AsyncAPI content", "empty",
                    "No AsyncAPI spec provided", "error"));
            return result;
        }

        JsonNode root;
        try {
            root = parseSpec(specRaw);
        } catch (Exception e) {
            result.addViolation(new ValidationViolation("spec", "valid AsyncAPI YAML/JSON",
                    "parse error", "Could not parse AsyncAPI spec: " + e.getMessage(), "error"));
            return result;
        }

        try {
            switch (test.getId()) {
                case "ASYNC-01" -> checkChannelSchemas(result, root);
                case "ASYNC-02" -> checkChannelOperations(result, root);
                case "ASYNC-03" -> checkPayloadTypes(result, root);
                default         -> checkChannelSchemas(result, root);
            }
        } catch (Exception e) {
            log.warn("AsyncAPI engine error on {}: {}", test.getId(), e.getMessage());
            result.addViolation(new ValidationViolation("engine", "no exception",
                    e.getClass().getSimpleName(), e.getMessage(), "error"));
        }

        return result;
    }

    private void checkChannelSchemas(TestCaseResult result, JsonNode root) {
        JsonNode channels = root.path("channels");
        if (channels.isMissingNode() || !channels.isObject()) {
            result.addViolation(new ValidationViolation("channels", "channels object", "missing",
                    "No channels defined in AsyncAPI spec", "error"));
            return;
        }

        channels.fields().forEachRemaining(entry -> {
            String channelName = entry.getKey();
            JsonNode channel = entry.getValue();

            boolean hasSchema = hasMessageSchema(channel, "subscribe")
                    || hasMessageSchema(channel, "publish");

            if (!hasSchema) {
                result.addViolation(new ValidationViolation(
                        channelName, "message schema", "none",
                        "Channel \"" + channelName + "\" has no message schema defined", "error"
                ));
            }
        });
    }

    private void checkChannelOperations(TestCaseResult result, JsonNode root) {
        JsonNode channels = root.path("channels");
        if (channels.isMissingNode()) return;

        channels.fields().forEachRemaining(entry -> {
            String channelName = entry.getKey();
            JsonNode channel = entry.getValue();

            boolean hasSubscribe = !channel.path("subscribe").isMissingNode();
            boolean hasPublish   = !channel.path("publish").isMissingNode();

            if (!hasSubscribe && !hasPublish) {
                result.addViolation(new ValidationViolation(
                        channelName, "subscribe or publish", "neither",
                        "Channel \"" + channelName + "\" has neither subscribe nor publish defined",
                        "error"
                ));
            }
        });
    }

    private void checkPayloadTypes(TestCaseResult result, JsonNode root) {
        JsonNode channels = root.path("channels");
        if (channels.isMissingNode()) return;

        for (String op : new String[]{"subscribe", "publish"}) {
            channels.fields().forEachRemaining(entry -> {
                String channelName = entry.getKey();
                JsonNode channel = entry.getValue();
                JsonNode payload = channel.path(op).path("message").path("payload");
                if (payload.isMissingNode()) return;

                JsonNode properties = payload.path("properties");
                if (properties.isMissingNode() || !properties.isObject()) return;

                properties.fields().forEachRemaining(propEntry -> {
                    String propName = propEntry.getKey();
                    JsonNode propNode = propEntry.getValue();
                    if (propNode.path("type").isMissingNode()
                            && propNode.path("$ref").isMissingNode()) {
                        result.addViolation(new ValidationViolation(
                                channelName + "/" + op + "/payload/" + propName,
                                "type or $ref",
                                "none",
                                "Property \"" + propName + "\" in channel \"" + channelName
                                + "\" has no type annotation",
                                "error"
                        ));
                    }
                });
            });
        }
    }

    private boolean hasMessageSchema(JsonNode channel, String operation) {
        JsonNode op = channel.path(operation);
        if (op.isMissingNode()) return false;
        JsonNode payload = op.path("message").path("payload");
        return !payload.isMissingNode();
    }

    private JsonNode parseSpec(String raw) throws Exception {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            return jsonMapper.readTree(trimmed);
        }
        return yamlMapper.readTree(trimmed);
    }
}
