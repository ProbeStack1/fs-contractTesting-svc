package com.forge.contracttesting.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean("projectWebClient")
    public WebClient projectWebClient(
            @Value("${services.project.base-url}") String baseUrl,
            @Value("${services.project.context-path}") String contextPath) {
        return WebClient.builder()
                .baseUrl(baseUrl + contextPath)
                .defaultHeader("Content-Type", "application/json")
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
    }

    @Bean("apiSpecWebClient")
    public WebClient apiSpecWebClient(
            @Value("${services.apispec.base-url}") String baseUrl,
            @Value("${services.apispec.context-path}") String contextPath) {
        return WebClient.builder()
                .baseUrl(baseUrl + contextPath)
                .defaultHeader("Content-Type", "application/json")
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
}
