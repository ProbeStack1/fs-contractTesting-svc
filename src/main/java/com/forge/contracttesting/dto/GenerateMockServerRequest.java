package com.forge.contracttesting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GenerateMockServerRequest {
    private String contractId;
    private String mockServiceName;
    private String microserviceId;
    private String specMetadataId;      // optional — if spec is stored externally
    private Boolean isPrivate;
    private Integer responseLatencyMs;
}
