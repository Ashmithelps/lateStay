package com.latestay.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Hotel photo uploads ({@code latestay.photos.*}). Processing runs in the imaging service (Node + sharp); the
 * results go to S3-compatible storage, Cloudflare R2 by default.
 *
 * @param imagingUrl the imaging service, e.g. http://latestay-imaging.flycast:8081 (IMAGING_URL)
 * @param imagingToken shared secret sent as a bearer token (IMAGING_TOKEN)
 * @param endpoint S3 API endpoint: https://<account>.r2.cloudflarestorage.com for R2 (R2_ENDPOINT)
 * @param region "auto" for R2
 * @param bucket the bucket (R2_BUCKET); blank = uploads are switched off
 * @param publicBaseUrl where the bucket is served publicly, e.g. https://photos.latestay.in (R2_PUBLIC_BASE_URL)
 */
@ConfigurationProperties("latestay.photos")
public record PhotoProperties(
        @DefaultValue("http://localhost:8081") String imagingUrl,
        @DefaultValue("") String imagingToken,
        @DefaultValue("") String endpoint,
        @DefaultValue("auto") String region,
        @DefaultValue("") String bucket,
        @DefaultValue("") String accessKeyId,
        @DefaultValue("") String secretAccessKey,
        @DefaultValue("") String publicBaseUrl,
        @DefaultValue("false") boolean pathStyle) {

    public boolean storageConfigured() {
        return !bucket.isBlank() && !endpoint.isBlank() && !publicBaseUrl.isBlank();
    }
}
