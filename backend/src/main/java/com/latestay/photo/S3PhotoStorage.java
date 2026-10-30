package com.latestay.photo;

import java.net.URI;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

/** Cloudflare R2 (or any S3 API: MinIO locally) through the AWS SDK with a custom endpoint. */
public class S3PhotoStorage implements PhotoStorage {

    private final S3Client s3;
    private final String bucket;
    private final String publicBaseUrl;

    public S3PhotoStorage(PhotoProperties p) {
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(p.endpoint()))
                .region(Region.of(p.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(p.accessKeyId(), p.secretAccessKey())))
                .forcePathStyle(p.pathStyle())
                // R2 and MinIO don't need the SDK's default extra checksums on every request.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        this.bucket = p.bucket();
        this.publicBaseUrl = p.publicBaseUrl().replaceAll("/+$", "");
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType)
                        // Keys are never reused (a new id per upload), so browsers and the CDN can keep them.
                        .cacheControl("public, max-age=31536000, immutable"),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public void delete(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        s3.deleteObjects(b -> b.bucket(bucket).delete(Delete.builder()
                .objects(keys.stream().map(k -> ObjectIdentifier.builder().key(k).build()).toList()).build()));
    }

    @Override
    public String publicUrl(String key) {
        return publicBaseUrl + "/" + key;
    }
}
