package com.forge.contracttesting.repository;

import com.forge.contracttesting.model.MockRequestLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface MockRequestLogRepository extends MongoRepository<MockRequestLog, String> {

    List<MockRequestLog> findByMockId(String mockId);

    Page<MockRequestLog> findByMockIdOrderByTimestampDesc(String mockId, Pageable pageable);

    // Case-insensitive match so "Simran Sandhya Dash" == "simran sandhya dash"
    @Query("{ 'mockId': ?0, 'consumerName': { $regex: ?1, $options: 'i' } }")
    List<MockRequestLog> findByMockIdAndConsumerName(String mockId, String consumerName);

    Page<MockRequestLog> findByMockIdAndPathAndMethodOrderByTimestampDesc(
            String mockId, String path, String method, Pageable pageable);

    List<MockRequestLog> findBySessionId(String sessionId);

    List<MockRequestLog> findByContractId(String contractId);

    void deleteByMockId(String mockId);
}
