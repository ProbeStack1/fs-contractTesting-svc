package com.forge.contracttesting.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@Document(collection = "consumers")
public class Consumer {

    @Id
    private String id;

    private String consumerName;
    private String pocName;
    private String pocEmail;
    private String smeName;
    private String smeEmail;
    private String apiTps;
    private String quota;
    private String rateLimiting;
    private String apiKeyInfo;
    private String status;        // ACTIVE | INACTIVE

    private String orgId;
    private Instant createdAt;
    private Instant updatedAt;
}
