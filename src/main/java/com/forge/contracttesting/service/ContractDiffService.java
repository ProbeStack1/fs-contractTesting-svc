package com.forge.contracttesting.service;

import com.forge.contracttesting.dto.BreakingChange;
import com.forge.contracttesting.dto.BreakingChangeSummary;
import com.forge.contracttesting.model.Contract;
import com.forge.contracttesting.repository.ContractRepository;
import com.forge.contracttesting.service.SpecParserService.EndpointInfo;
import com.forge.contracttesting.service.SpecParserService.ParsedSpec;
import io.swagger.v3.oas.models.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares two versions of the same spec and identifies breaking changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractDiffService {

    private final SpecParserService specParserService;
    private final ContractRepository contractRepository;

    /**
     * Diffs the new spec against whatever is currently stored for this contract.
     * Useful when the frontend sends an updated spec and wants to know if it breaks consumers.
     */
    public BreakingChangeSummary diffWithStored(String contractId, String specV2) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new NoSuchElementException("Contract not found: " + contractId));

        if (contract.getSpecRaw() == null || contract.getSpecRaw().isBlank()) {
            throw new IllegalArgumentException(
                    "Contract " + contractId + " has no stored spec to compare against. Upload a spec first.");
        }

        return diff(contractId, contract.getSpecRaw(), specV2);
    }

    public BreakingChangeSummary diff(String contractId, String specV1, String specV2) {
        ParsedSpec v1 = specParserService.parse(specV1, "v1");
        ParsedSpec v2 = specParserService.parse(specV2, "v2");
        return diff(contractId, v1, v2);
    }

    public BreakingChangeSummary diff(String contractId, ParsedSpec v1, ParsedSpec v2) {
        BreakingChangeSummary summary = new BreakingChangeSummary();
        summary.setContractId(contractId);

        diffEndpoints(v1, v2, summary);
        diffSchemas(v1, v2, summary);

        summary.setHasBreakingChanges(!summary.getBreakingChanges().isEmpty());
        return summary;
    }

    private void diffEndpoints(ParsedSpec v1, ParsedSpec v2, BreakingChangeSummary summary) {
        Set<String> v1Keys = endpointKeys(v1);
        Set<String> v2Keys = endpointKeys(v2);

        // Removed endpoints → breaking
        v1Keys.stream()
                .filter(k -> !v2Keys.contains(k))
                .forEach(k -> summary.getBreakingChanges().add(
                        new BreakingChange("ENDPOINT_REMOVED", k, k, null)));

        // Added endpoints → non-breaking
        v2Keys.stream()
                .filter(k -> !v1Keys.contains(k))
                .forEach(k -> summary.getNonBreakingChanges().add(
                        new BreakingChange("ENDPOINT_ADDED", k, null, k)));
    }

    @SuppressWarnings("unchecked")
    private void diffSchemas(ParsedSpec v1, ParsedSpec v2, BreakingChangeSummary summary) {
        Map<String, Schema<?>> v1Schemas = v1.getSchemas();
        Map<String, Schema<?>> v2Schemas = v2.getSchemas();

        // Removed schemas → breaking
        v1Schemas.keySet().stream()
                .filter(name -> !v2Schemas.containsKey(name))
                .forEach(name -> summary.getBreakingChanges().add(
                        new BreakingChange("SCHEMA_REMOVED", name, name, null)));

        // Added schemas → non-breaking
        v2Schemas.keySet().stream()
                .filter(name -> !v1Schemas.containsKey(name))
                .forEach(name -> summary.getNonBreakingChanges().add(
                        new BreakingChange("SCHEMA_ADDED", name, null, name)));

        // Field-level diff for schemas present in both
        v1Schemas.keySet().stream()
                .filter(v2Schemas::containsKey)
                .forEach(schemaName -> diffSchemaFields(
                        schemaName,
                        v1Schemas.get(schemaName),
                        v2Schemas.get(schemaName),
                        summary));
    }

    @SuppressWarnings("unchecked")
    private void diffSchemaFields(String schemaName, Schema<?> v1Schema, Schema<?> v2Schema,
                                   BreakingChangeSummary summary) {
        Map<String, Schema> v1Props = v1Schema.getProperties();
        Map<String, Schema> v2Props = v2Schema.getProperties();

        List<String> v1Required = v1Schema.getRequired() != null ? v1Schema.getRequired() : List.of();
        List<String> v2Required = v2Schema.getRequired() != null ? v2Schema.getRequired() : List.of();

        if (v1Props == null) v1Props = Map.of();
        if (v2Props == null) v2Props = Map.of();

        final Map<String, Schema> finalV1Props = v1Props;
        final Map<String, Schema> finalV2Props = v2Props;

        // Removed fields → breaking
        finalV1Props.keySet().stream()
                .filter(field -> !finalV2Props.containsKey(field))
                .forEach(field -> summary.getBreakingChanges().add(
                        new BreakingChange("FIELD_REMOVED",
                                schemaName + "." + field,
                                ((Schema<?>) finalV1Props.get(field)).getType(),
                                null)));

        // Added required fields → breaking (existing consumers won't send them)
        finalV2Props.keySet().stream()
                .filter(field -> !finalV1Props.containsKey(field) && v2Required.contains(field))
                .forEach(field -> summary.getBreakingChanges().add(
                        new BreakingChange("REQUIRED_FIELD_ADDED",
                                schemaName + "." + field,
                                null,
                                ((Schema<?>) finalV2Props.get(field)).getType())));

        // Added optional fields → non-breaking
        finalV2Props.keySet().stream()
                .filter(field -> !finalV1Props.containsKey(field) && !v2Required.contains(field))
                .forEach(field -> summary.getNonBreakingChanges().add(
                        new BreakingChange("FIELD_ADDED",
                                schemaName + "." + field,
                                null,
                                ((Schema<?>) finalV2Props.get(field)).getType())));

        // Type changes on existing fields → breaking
        finalV1Props.keySet().stream()
                .filter(finalV2Props::containsKey)
                .forEach(field -> {
                    String t1 = ((Schema<?>) finalV1Props.get(field)).getType();
                    String t2 = ((Schema<?>) finalV2Props.get(field)).getType();
                    if (!Objects.equals(t1, t2)) {
                        summary.getBreakingChanges().add(
                                new BreakingChange("FIELD_TYPE_CHANGED",
                                        schemaName + "." + field, t1, t2));
                    }
                });

        // Field became required → breaking for consumers that don't send it
        v2Required.stream()
                .filter(field -> finalV1Props.containsKey(field) && !v1Required.contains(field))
                .forEach(field -> summary.getBreakingChanges().add(
                        new BreakingChange("FIELD_BECAME_REQUIRED",
                                schemaName + "." + field,
                                "optional",
                                "required")));
    }

    private Set<String> endpointKeys(ParsedSpec spec) {
        return spec.getEndpoints().stream()
                .map(ep -> ep.getMethod() + " " + ep.getPath())
                .collect(Collectors.toSet());
    }
}
