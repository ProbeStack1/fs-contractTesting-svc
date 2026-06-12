package com.forge.contracttesting.repository;

import com.forge.contracttesting.model.MockServerApprovalHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MockServerApprovalHistoryRepository extends MongoRepository<MockServerApprovalHistory, String> {

    List<MockServerApprovalHistory> findByApproverEmailIgnoreCaseOrderBySentAtDesc(String approverEmail);

    List<MockServerApprovalHistory> findBySentByOrderBySentAtDesc(String sentBy);

    Page<MockServerApprovalHistory> findByMockServerIdOrderBySentAtDesc(String mockServerId, Pageable pageable);
}
