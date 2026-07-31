package com.forge.contracttesting.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.forge.contracttesting.repository.SecretRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads runtime credentials from the shared "secrets" Mongo collection
 * (documents shaped { key, value }) rather than environment-variable-backed
 * properties, so credentials can be rotated without a redeploy.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SecretsService {

    private final SecretRepository secretRepository;

    public Optional<String> getValue(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return secretRepository.findByKey(key).map(secret -> secret.getValue());
    }

    public String requireValue(String key) {
        return getValue(key)
                .filter(v -> v != null && !v.isBlank())
                .orElseThrow(() -> {
                    log.warn("Secret not found or empty: {}", key);
                    return new IllegalStateException("Missing required secret: " + key);
                });
    }
}
