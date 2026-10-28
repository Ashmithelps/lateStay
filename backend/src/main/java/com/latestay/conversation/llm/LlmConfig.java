package com.latestay.conversation.llm;

import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    LlmClient llmClient(LlmProperties properties, RestClient.Builder builder) {
        LlmClient client = create(properties, builder);
        log.info("LLM client: {}", client.getClass().getSimpleName());
        return client;
    }

    /** Builds the client chosen by {@code LLM_PROVIDER}; falls back to {@link NoLlmClient}. */
    public static LlmClient create(LlmProperties properties, RestClient.Builder builder) {
        String provider = properties.provider() == null ? "none" : properties.provider().trim().toLowerCase();
        if (provider.equals("none")) {
            return new NoLlmClient("LLM_PROVIDER=none");
        }
        if (isBlank(properties.apiKey())) {
            return new NoLlmClient("LLM_API_KEY is not set");
        }

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        requestFactory.setReadTimeout(properties.timeout());

        return switch (provider) {
            case "anthropic" -> new AnthropicLlmClient(
                    builder.clone().baseUrl(orDefault(properties.baseUrl(), AnthropicLlmClient.DEFAULT_BASE_URL))
                            .requestFactory(requestFactory).build(),
                    orDefault(properties.model(), AnthropicLlmClient.DEFAULT_MODEL),
                    properties.apiKey());
            case "openai" -> {
                if (isBlank(properties.model())) {
                    yield new NoLlmClient("LLM_MODEL is required for the openai provider");
                }
                yield new OpenAiCompatibleLlmClient(
                        builder.clone().baseUrl(orDefault(properties.baseUrl(), OpenAiCompatibleLlmClient.DEFAULT_BASE_URL))
                                .requestFactory(requestFactory).build(),
                        properties.model(),
                        properties.apiKey());
            }
            default -> new NoLlmClient("unknown LLM_PROVIDER '" + provider + "'");
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String orDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }
}
