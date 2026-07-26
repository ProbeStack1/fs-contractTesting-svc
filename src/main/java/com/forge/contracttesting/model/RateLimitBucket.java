package com.forge.contracttesting.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One document per (mockId, consumerName, epoch-second window). Counts are
 * incremented atomically via MongoTemplate.findAndModify so the limit holds
 * across all service replicas, not just the one that handled the request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "rate_limit_buckets")
public class RateLimitBucket {

    @Id
    private String id;              // "{mockId}:{consumerName}:{windowEpochSecond}"

    private long count;

    @Indexed(name = "rate_limit_bucket_ttl", expireAfterSeconds = 0)
    private Instant expiresAt;
}
