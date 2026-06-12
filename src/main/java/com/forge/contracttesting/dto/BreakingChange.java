package com.forge.contracttesting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BreakingChange {
    private String type;           // ENDPOINT_REMOVED | FIELD_TYPE_CHANGED | REQUIRED_FIELD_ADDED | FIELD_REMOVED
    private String path;
    private String previousValue;
    private String newValue;
}
