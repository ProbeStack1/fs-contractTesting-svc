package com.forge.contracttesting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CreateMockServerRequest {
    private String name;
    private String contractId;
    private String microserviceId;
    private Boolean isPrivate;
    private Integer delayMs;
}
