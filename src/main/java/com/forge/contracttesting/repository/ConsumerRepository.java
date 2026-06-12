package com.forge.contracttesting.repository;

import com.forge.contracttesting.model.Consumer;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ConsumerRepository extends MongoRepository<Consumer, String> {
    List<Consumer> findByOrgId(String orgId);
    List<Consumer> findByConsumerName(String consumerName);
}
