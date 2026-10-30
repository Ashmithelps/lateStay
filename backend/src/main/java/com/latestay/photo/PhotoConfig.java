package com.latestay.photo;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PhotoProperties.class)
class PhotoConfig {

    @Bean
    @ConditionalOnMissingBean
    ImageProcessor imageProcessor(PhotoProperties properties) {
        return new HttpImageProcessor(properties);
    }

    /** Without R2 settings, uploads answer 503 (existing photo URLs keep working). */
    @Bean
    @ConditionalOnMissingBean
    PhotoStorage photoStorage(PhotoProperties properties) {
        if (properties.storageConfigured()) {
            return new S3PhotoStorage(properties);
        }
        return new PhotoStorage() {
            @Override
            public void put(String key, byte[] bytes, String contentType) {
                throw new PhotoRejectedException("STORAGE_OFF",
                        "Photo storage isn't set up: set R2_ENDPOINT, R2_BUCKET, R2_ACCESS_KEY_ID, "
                                + "R2_SECRET_ACCESS_KEY and R2_PUBLIC_BASE_URL.", 503);
            }

            @Override
            public void delete(List<String> keys) {
            }

            @Override
            public String publicUrl(String key) {
                return key;
            }
        };
    }
}
