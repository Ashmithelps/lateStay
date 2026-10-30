package com.latestay.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The real S3 client against Adobe S3Mock (the same S3 API as Cloudflare R2): put with content type and cache
 * headers, public read, delete.
 */
@Testcontainers
class S3PhotoStorageIT {

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:4.7.0")
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "photos")
            .withExposedPorts(9090)
            .waitingFor(Wait.forHttp("/").forPort(9090));

    @Test
    void putsPublicWebpFilesAndDeletesThem() throws Exception {
        String endpoint = "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
        PhotoProperties properties = new PhotoProperties("", "", endpoint, "auto", "photos", "test-key", "test-secret",
                endpoint + "/photos", true);
        S3PhotoStorage storage = new S3PhotoStorage(properties);

        storage.put("hotels/h1/p1-400.webp", "RIFFwebp".getBytes(), "image/webp");
        String url = storage.publicUrl("hotels/h1/p1-400.webp");
        assertThat(url).isEqualTo(endpoint + "/photos/hotels/h1/p1-400.webp");

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("RIFFwebp".getBytes());
        assertThat(response.headers().firstValue("Content-Type")).hasValue("image/webp");
        assertThat(response.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(v -> assertThat(v).contains("immutable"));

        storage.delete(List.of("hotels/h1/p1-400.webp"));
        assertThat(http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode()).isEqualTo(404);
    }
}
