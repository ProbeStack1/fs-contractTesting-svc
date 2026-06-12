package com.forge.contracttesting.repository;

import com.forge.contracttesting.model.PactFile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PactFileRepository extends MongoRepository<PactFile, String> {

    List<PactFile> findByMockServerIdAndConsumerName(String mockServerId, String consumerName);

    List<PactFile> findByProviderName(String providerName);

    List<PactFile> findByContractId(String contractId);

    List<PactFile> findByConsumerName(String consumerName);
}
