package com.forge.contracttesting.repository;

import com.forge.contracttesting.model.MockServerApproval;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface MockServerApprovalRepository extends MongoRepository<MockServerApproval, String> {

    Optional<MockServerApproval> findByMockServerIdAndType(String mockServerId, String type);

    List<MockServerApproval> findByMockServerId(String mockServerId);
}
