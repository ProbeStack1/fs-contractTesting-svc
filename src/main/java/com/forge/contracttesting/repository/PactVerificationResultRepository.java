package com.forge.contracttesting.repository;

import com.forge.contracttesting.model.PactVerificationResult;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PactVerificationResultRepository extends MongoRepository<PactVerificationResult, String> {

    List<PactVerificationResult> findByPactFileId(String pactFileId);

    List<PactVerificationResult> findByProviderNameAndProviderVersionAndEnvironment(
            String providerName, String providerVersion, String environment);

    List<PactVerificationResult> findByContractId(String contractId);
}
