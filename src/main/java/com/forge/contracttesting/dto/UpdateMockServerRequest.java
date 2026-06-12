package com.forge.contracttesting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UpdateMockServerRequest {
    private String name;
    private Boolean isPrivate;
    private Integer delayMs;
}
