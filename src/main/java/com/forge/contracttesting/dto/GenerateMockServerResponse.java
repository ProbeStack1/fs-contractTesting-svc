package com.forge.contracttesting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateMockServerResponse {
    private MockServerResponse mockServer;
    private List<EndpointResponse> endpoints;
    private int totalEndpoints;
}
