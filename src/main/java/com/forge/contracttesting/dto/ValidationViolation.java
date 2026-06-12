package com.forge.contracttesting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidationViolation {
    private String field;
    private String expectedType;
    private String actualType;
    private String reason;
    private String severity;     // error | warning
}
