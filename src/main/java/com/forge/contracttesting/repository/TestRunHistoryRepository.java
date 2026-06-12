package com.forge.contracttesting.repository;

import com.forge.contracttesting.model.TestRunHistory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TestRunHistoryRepository extends MongoRepository<TestRunHistory, String> {
    List<TestRunHistory> findByContractId(String contractId, Sort sort);
    List<TestRunHistory> findTop500ByContractIdOrderByRunAtDesc(String contractId);
}
