package com.forge.contracttesting.service;

import com.forge.contracttesting.dto.ConsumerDto;
import com.forge.contracttesting.model.Consumer;
import com.forge.contracttesting.model.Contract;
import com.forge.contracttesting.repository.ConsumerRepository;
import com.forge.contracttesting.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Reads/writes from the existing 'consumers' collection in probestack-forgestudio.
 * The contract testing service owns create/update/delete for this collection;
 * it shares read access with any other service using the same DB.
 */
@Service
@RequiredArgsConstructor
public class ConsumerService {

    private final ConsumerRepository consumerRepository;
    private final ContractRepository contractRepository;

    public List<Consumer> listAll() {
        return consumerRepository.findAll();
    }

    public Consumer getById(String id) {
        return consumerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Consumer not found: " + id));
    }

    public Consumer create(ConsumerDto dto) {
        Consumer c = new Consumer();
        c.setId(UUID.randomUUID().toString());
        c.setConsumerName(dto.getConsumerName());
        c.setPocName(dto.getPocName());
        c.setPocEmail(dto.getPocEmail());
        c.setSmeName(dto.getSmeName());
        c.setSmeEmail(dto.getSmeEmail());
        c.setApiTps(dto.getApiTps());
        c.setQuota(dto.getQuota());
        c.setRateLimiting(dto.getRateLimiting());
        c.setApiKeyInfo(dto.getApiKeyInfo());
        c.setStatus("ACTIVE");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return consumerRepository.save(c);
    }

    public Consumer update(String id, ConsumerDto dto) {
        Consumer c = getById(id);
        c.setConsumerName(dto.getConsumerName());
        c.setPocName(dto.getPocName());
        c.setPocEmail(dto.getPocEmail());
        c.setSmeName(dto.getSmeName());
        c.setSmeEmail(dto.getSmeEmail());
        c.setApiTps(dto.getApiTps());
        c.setQuota(dto.getQuota());
        c.setRateLimiting(dto.getRateLimiting());
        c.setApiKeyInfo(dto.getApiKeyInfo());
        c.setUpdatedAt(Instant.now());
        return consumerRepository.save(c);
    }

    public void delete(String id) {
        consumerRepository.deleteById(id);
    }

    /** Returns all contracts where consumerName matches this consumer's consumerName. */
    public List<com.forge.contracttesting.model.Contract> getContractsByConsumer(String consumerId) {
        Consumer consumer = getById(consumerId);
        if (consumer.getConsumerName() == null || consumer.getConsumerName().isBlank()) {
            return List.of();
        }
        return contractRepository.findByConsumerName(consumer.getConsumerName());
    }
}
