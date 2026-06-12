package com.forge.contracttesting.repository;

import com.forge.contracttesting.model.Contract;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ContractRepository extends MongoRepository<Contract, String> {
    List<Contract> findByOrgId(String orgId);
    List<Contract> findByOrgIdAndType(String orgId, String type);
    List<Contract> findByProviderName(String providerName);
    List<Contract> findByConsumerName(String consumerName);
}
