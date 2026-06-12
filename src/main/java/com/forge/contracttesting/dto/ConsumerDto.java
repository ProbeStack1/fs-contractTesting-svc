package com.forge.contracttesting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

@Data
@NoArgsConstructor
public class ConsumerDto {

    @NotBlank(message = "Consumer name is required")
    private String consumerName;

    private String pocName;
    private String pocEmail;
    private String smeName;
    private String smeEmail;
    private String apiTps;
    private String quota;
    private String rateLimiting;
    private String apiKeyInfo;
}
