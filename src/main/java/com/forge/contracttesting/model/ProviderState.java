package com.forge.contracttesting.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

@Data
@NoArgsConstructor
@Document(collection = "provider_states")
public class ProviderState {

    @Id
    private String id;

    private String contractId;

    private String name;                    // "user with id 42 exists"
    private String setupEndpoint;           // POST /pact/provider-states on the provider
    private String teardownEndpoint;        // optional cleanup endpoint
    private Map<String, Object> params;     // data to seed
}
