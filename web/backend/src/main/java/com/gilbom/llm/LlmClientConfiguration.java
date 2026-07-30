package com.gilbom.llm;

import java.net.URI;
import java.net.http.HttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
public class LlmClientConfiguration {

    @Bean
    LlmPolicyGate llmPolicyGate() {
        return new LlmPolicyGate();
    }

    @Bean
    LlmPromptCatalog llmPromptCatalog() {
        return new LlmPromptCatalog();
    }

    @Bean
    LlmPolicyDecision llmPolicyDecision(
            LlmProperties properties,
            LlmPolicyGate policyGate
    ) {
        return policyGate.evaluate(properties);
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient llmClient(
            LlmProperties properties,
            LlmPolicyDecision decision,
            LlmPromptCatalog promptCatalog,
            ObjectMapper objectMapper
    ) {
        if (!decision.enabled()) {
            return new DisabledLlmClient(decision);
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(
                        HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(
                properties.getReadTimeout());
        String baseUrl = URI.create(
                        properties.getBaseUrl().trim())
                .toString();
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        return new GmsLlmClient(
                restClient,
                objectMapper,
                properties,
                promptCatalog);
    }
}
