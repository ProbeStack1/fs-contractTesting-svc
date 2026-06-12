package com.forge.contracttesting.repository;

import com.forge.contracttesting.model.MockEndpoint;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface MockEndpointRepository extends MongoRepository<MockEndpoint, String> {

    List<MockEndpoint> findByMockId(String mockId);

    List<MockEndpoint> findByMockIdAndIsActiveTrue(String mockId);

    Optional<MockEndpoint> findByMockIdAndPathAndMethod(String mockId, String path, String method);

    boolean existsByMockIdAndPathAndMethod(String mockId, String path, String method);

    void deleteByMockId(String mockId);
}
