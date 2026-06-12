package com.forge.contracttesting.dto;

import com.forge.contracttesting.model.ContractStatus;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ContractDto {

    @NotBlank(message = "Contract name is required")
    private String name;

    private String type = "openapi";
    private String description;

    // Gap 1 fields — all optional on creation
    private String baseUrl;
    private String providerName;
    private String consumerName;
    private String environment;
    private String mockServerId;
    private String pactBrokerId;
    private ContractStatus status;
    private String authType;
    private String authValue;
}
