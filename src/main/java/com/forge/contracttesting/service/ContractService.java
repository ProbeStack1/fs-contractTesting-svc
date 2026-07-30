package com.forge.contracttesting.service;

import com.forge.contracttesting.dto.ContractDto;
import com.forge.contracttesting.dto.RunTestResult;
import com.forge.contracttesting.model.Consumer;
import com.forge.contracttesting.model.Contract;
import com.forge.contracttesting.model.ContractStatus;
import com.forge.contracttesting.model.ContractTest;
import com.forge.contracttesting.model.PactFile;
import com.forge.contracttesting.repository.ConsumerRepository;
import com.forge.contracttesting.repository.ContractRepository;
import com.forge.contracttesting.repository.PactFileRepository;
import com.forge.contracttesting.service.SpecParserService.ParsedSpec;
import io.swagger.v3.oas.models.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

    private static final String DEFAULT_ORG = "default";

    private final ContractRepository contractRepository;
    private final ConsumerRepository consumerRepository;
    private final PactFileRepository pactFileRepository;
    private final SpecParserService specParserService;
    private final ContractTestRunner testRunner;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * List contracts with optional filters.
     * orgId: if null/blank or starts with "admin@" → return all contracts (admin view).
     *        otherwise → scoped to that org only.
     */
    public List<Contract> listAll(String orgId, String type, String status,
                                   String providerName, String consumerName) {
        boolean isAdmin = orgId == null || orgId.isBlank() || orgId.startsWith("admin@");

        List<Contract> base = isAdmin
                ? contractRepository.findAll()
                : contractRepository.findByOrgId(orgId);

        return base.stream()
                .filter(c -> type == null         || type.isBlank()
                        || type.equalsIgnoreCase(c.getType()))
                .filter(c -> status == null       || status.isBlank()
                        || (c.getStatus() != null && c.getStatus().name().equalsIgnoreCase(status)))
                .filter(c -> providerName == null || providerName.isBlank()
                        || providerName.equalsIgnoreCase(c.getProviderName()))
                .filter(c -> consumerName == null || consumerName.isBlank()
                        || consumerName.equalsIgnoreCase(c.getConsumerName()))
                .collect(Collectors.toList());
    }

    /** Kept for backward-compat — returns all contracts (admin view). */
    public List<Contract> listAll() {
        return contractRepository.findAll();
    }

    public Contract getById(String id) {
        return contractRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Contract not found: " + id));
    }

    public Contract create(ContractDto dto) {
        Contract c = new Contract();
        c.setId(UUID.randomUUID().toString());
        c.setName(dto.getName());
        c.setType(dto.getType() != null ? dto.getType() : "openapi");
        c.setDescription(dto.getDescription());
        c.setOrgId(DEFAULT_ORG);
        c.setTests(defaultTests(c.getType()));
        applyDtoFields(c, dto);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return contractRepository.save(c);
    }

    public Contract update(String id, ContractDto dto) {
        Contract c = getById(id);
        c.setName(dto.getName());
        if (dto.getType() != null) c.setType(dto.getType());
        c.setDescription(dto.getDescription());
        applyDtoFields(c, dto);
        c.setUpdatedAt(Instant.now());
        return contractRepository.save(c);
    }

    private void applyDtoFields(Contract c, ContractDto dto) {
        if (dto.getBaseUrl()      != null) c.setBaseUrl(dto.getBaseUrl());
        if (dto.getProviderName() != null) c.setProviderName(dto.getProviderName());
        if (dto.getConsumerName() != null) c.setConsumerName(dto.getConsumerName());
        if (dto.getEnvironment()  != null) c.setEnvironment(dto.getEnvironment());
        if (dto.getMockServerId() != null) c.setMockServerId(dto.getMockServerId());
        if (dto.getPactBrokerId() != null) c.setPactBrokerId(dto.getPactBrokerId());
        if (dto.getStatus()       != null) c.setStatus(dto.getStatus());
        if (dto.getAuthType()     != null) c.setAuthType(dto.getAuthType());
        if (dto.getAuthValue()    != null) c.setAuthValue(dto.getAuthValue());
    }

    public void delete(String id) {
        contractRepository.deleteById(id);
    }

    // ── Spec upload ───────────────────────────────────────────────────────────

    public Contract uploadSpec(String id, String rawSpec, String filename) {
        Contract c = getById(id);

        ParsedSpec parsed = specParserService.parse(rawSpec, filename);

        c.setSpecRaw(rawSpec);
        c.setSpecFilename(filename);
        c.setSpecVersion(parsed.getVersion());
        c.setEndpointCount(parsed.getEndpoints().size());
        c.setSchemaCount(parsed.getSchemas().size());
        c.setTests(generateTests(parsed, c.getType()));
        c.setUpdatedAt(Instant.now());

        return contractRepository.save(c);
    }

    // ── Validator rules upload ────────────────────────────────────────────────

    public Contract uploadValidatorRules(String id, List<Map<String, Object>> rules, List<ContractTest> generatedTests) {
        Contract c = getById(id);
        c.setValidatorRules(rules);

        // Upsert by test id: replace any existing test that shares an id with an
        // incoming test, keep everything else untouched. Category-based filtering
        // is not reliable here since callers are not guaranteed to tag tests with
        // a "validator" category, and blindly appending duplicates test ids on
        // every save (inflating run totals).
        Set<String> incomingIds = generatedTests.stream()
                .map(ContractTest::getId)
                .collect(Collectors.toSet());

        List<ContractTest> existing = c.getTests().stream()
                .filter(t -> !incomingIds.contains(t.getId()))
                .toList();

        List<ContractTest> merged = new ArrayList<>(existing);
        merged.addAll(generatedTests);
        c.setTests(merged);
        c.setUpdatedAt(Instant.now());

        return contractRepository.save(c);
    }

    // ── Status transition ─────────────────────────────────────────────────────

    public Contract updateStatus(String id, ContractStatus newStatus) {
        Contract c = getById(id);
        c.setStatus(newStatus);
        c.setUpdatedAt(Instant.now());
        log.info("Contract {} status changed to {}", id, newStatus);
        return contractRepository.save(c);
    }

    // ── Spec access ───────────────────────────────────────────────────────────

    public String getSpec(String id) {
        Contract c = getById(id);
        if (c.getSpecRaw() == null || c.getSpecRaw().isBlank()) {
            throw new NoSuchElementException("No spec uploaded for contract: " + id);
        }
        return c.getSpecRaw();
    }

    // ── Consumer dependency map ───────────────────────────────────────────────

    /**
     * Returns all consumers whose pacts target the provider in this contract,
     * plus any consumer matched by the contract's own consumerName field.
     */
    public List<Consumer> getConsumersForContract(String contractId) {
        Contract contract = getById(contractId);

        // Collect consumer names from generated pact files for this contract
        List<PactFile> pacts = pactFileRepository.findByContractId(contractId);
        Set<String> names = pacts.stream()
                .map(PactFile::getConsumerName)
                .collect(Collectors.toSet());

        // Also include the contract's own explicit consumerName if set
        if (contract.getConsumerName() != null && !contract.getConsumerName().isBlank()) {
            names.add(contract.getConsumerName());
        }

        if (names.isEmpty()) return List.of();

        // Match to Consumer documents by consumerName
        return names.stream()
                .flatMap(name -> consumerRepository.findByConsumerName(name).stream())
                .collect(Collectors.toList());
    }

    // ── Run tests ─────────────────────────────────────────────────────────────

    public RunTestResult runTests(String id) {
        Contract c = getById(id);
        return testRunner.run(c);
    }

    // ── Test generation ───────────────────────────────────────────────────────

    private List<ContractTest> generateTests(ParsedSpec spec, String contractType) {
        List<ContractTest> tests = new ArrayList<>();

        // Core schema tests (always added when a spec is present)
        tests.add(test("OA-01", "Response schema completeness", "schema",
                "Every endpoint defines at least one 2xx response schema",
                "All endpoints have response schemas"));
        tests.add(test("OA-02", "Required field definitions", "schema",
                "Required fields in schemas are properly typed",
                "All required fields defined in properties"));
        tests.add(test("OA-03", "Field type coverage", "types",
                "Every schema field has a valid OpenAPI type annotation",
                "type ∈ {string, integer, number, boolean, array, object}"));
        tests.add(test("OA-04", "Mutating endpoint request bodies", "schema",
                "POST/PUT/PATCH endpoints must define a requestBody",
                "requestBody defined for all mutating operations"));
        tests.add(test("OA-05", "Security scheme consistency", "security",
                "Security schemes referenced in operations must be declared in components",
                "No undefined security scheme references"));
        tests.add(test("OA-06", "Content-Type header check", "headers",
                "Non-DELETE endpoints declare application/json for success responses",
                "All non-DELETE endpoints declare a JSON content-type"));

        // Per-endpoint tests (up to 10)
        int epCounter = 1;
        for (SpecParserService.EndpointInfo ep : spec.getEndpoints()) {
            if (epCounter > 10) break;
            ContractTest t = new ContractTest();
            t.setId("EP-" + String.format("%02d", epCounter++));
            t.setName(ep.getMethod() + " " + ep.getPath());
            t.setCategory("endpoint");
            t.setMethod(ep.getMethod());
            t.setEndpoint(ep.getMethod() + " " + ep.getPath());
            t.setDescription(ep.getSummary() != null ? ep.getSummary()
                    : "Contract for " + ep.getMethod() + " " + ep.getPath());
            t.setAssertion("Response schema fully defined and typed");
            tests.add(t);
        }

        return tests;
    }

    private List<ContractTest> defaultTests(String contractType) {
        return switch (contractType) {
            case "graphql" -> List.of(
                    test("GQL-01", "Type field completeness", "schema", "All types validated for typed fields", "All fields have explicit types"),
                    test("GQL-02", "Query operations defined", "query", "Query operations present", "Query type has at least one operation"),
                    test("GQL-03", "Mutation operations defined", "mutation", "Mutation operations present", "Mutation type has at least one operation")
            );
            case "grpc" -> List.of(
                    test("GRPC-01", "Service definition completeness", "schema", "All services have RPC methods", "At least one RPC method per service"),
                    test("GRPC-02", "Message field definitions", "types", "All messages have typed fields", "All message fields have type annotations"),
                    test("GRPC-03", "RPC request/response types", "schema", "Each RPC references defined message types", "All request/response types are defined messages")
            );
            case "async" -> List.of(
                    test("ASYNC-01", "Channel schema completeness", "schema", "All channels define message schemas", "All channels define a message payload schema"),
                    test("ASYNC-02", "Subscribe/Publish operations", "schema", "Each channel defines at least one operation", "Channel has subscribe or publish"),
                    test("ASYNC-03", "Message payload types", "types", "All payload properties are typed", "All payload properties are typed")
            );
            default -> List.of(
                    test("OA-01", "Response schema completeness", "schema", "All endpoints define response schemas", "Every endpoint has at least one response schema"),
                    test("OA-02", "Required field definitions", "schema", "Required fields in schemas are properly typed", "All required fields defined in properties"),
                    test("OA-03", "Field type coverage", "types", "Every schema field has a valid OpenAPI type", "type ∈ {string, integer, number, boolean, array, object}"),
                    test("OA-04", "Mutating endpoint request bodies", "schema", "POST/PUT/PATCH must define requestBody", "requestBody defined for all mutating operations"),
                    test("OA-05", "Security scheme consistency", "security", "Security schemes must be declared", "No undefined security scheme references"),
                    test("OA-06", "Content-Type header check", "headers", "Non-DELETE endpoints declare application/json", "All non-DELETE endpoints declare a JSON content-type")
            );
        };
    }

    private ContractTest test(String id, String name, String category, String description, String assertion) {
        ContractTest t = new ContractTest();
        t.setId(id);
        t.setName(name);
        t.setCategory(category);
        t.setDescription(description);
        t.setAssertion(assertion);
        return t;
    }
}
