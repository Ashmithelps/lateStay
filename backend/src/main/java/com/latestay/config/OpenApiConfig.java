package com.latestay.config;

import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DTOs are records and Jackson writes every component (nulls included), so every property is required; the few
 * that can be null say so with {@code @Schema(nullable = true)}. This keeps the generated TypeScript types exact.
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenApiCustomizer everyPropertyRequired() {
        return openApi -> {
            openApi.info(new Info().title("LateStay public API").version("v1"));
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
                if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                    schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
                }
            }
        };
    }
}
