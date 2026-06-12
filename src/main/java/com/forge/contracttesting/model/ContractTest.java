package com.forge.contracttesting.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ContractTest {

    private String id;
    private String name;
    private String category;      // schema | types | breaking | headers | endpoint | validator | security
    private String method;
    private String endpoint;
    private String description;
    private String assertion;
    private String field;         // for validator rules
    private String ruleType;      // required | type | format | enum | range | length | pattern
    private String severity;      // error | warning
    private String ruleId;

    // Typed constraint fields (Gap 9) — populated by frontend rule builder
    private String expectedType;          // string | integer | number | boolean | array | object
    private String expectedFormat;        // date-time | email | uuid | ...
    private List<String> expectedEnumValues;
    private Double minimumValue;
    private Double maximumValue;
    private Integer minLength;
    private Integer maxLength;
    private String patternRegex;
    private Boolean isRequired;
}
