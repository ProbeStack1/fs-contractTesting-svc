package com.forge.contracttesting.repository;

import com.forge.contracttesting.model.MockServer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface MockServerRepository extends MongoRepository<MockServer, String> {

    Optional<MockServer> findByMockUrl(String mockUrl);

    boolean existsByMockUrl(String mockUrl);

    List<MockServer> findByMicroserviceId(String microserviceId, Pageable pageable);

    Optional<MockServer> findByMicroserviceIdAndSpecMetadataId(String microserviceId, String specMetadataId);

    Optional<MockServer> findByContractId(String contractId);
}
