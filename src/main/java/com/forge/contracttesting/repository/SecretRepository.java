package com.forge.contracttesting.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.forge.contracttesting.model.Secret;

@Repository
public interface SecretRepository extends MongoRepository<Secret, String> {

    Optional<Secret> findByKey(String key);
}
