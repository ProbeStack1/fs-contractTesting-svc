package com.forge.contracttesting.repository;

import com.forge.contracttesting.model.ProviderState;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ProviderStateRepository extends MongoRepository<ProviderState, String> {

    List<ProviderState> findByContractId(String contractId);

    Optional<ProviderState> findByContractIdAndName(String contractId, String name);
}
