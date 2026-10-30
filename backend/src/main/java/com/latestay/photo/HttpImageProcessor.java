package com.latestay.photo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Calls the imaging service: POST /process with the raw photo, JSON back with base64 WebP variants. */
class HttpImageProcessor implements ImageProcessor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;

    HttpImageProcessor(PhotoProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.imagingUrl()).requestFactory(factory);
        if (!properties.imagingToken().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.imagingToken());
        }
        this.client = builder.build();
    }

    @Override
    public Processed process(byte[] photo) {
        String body = client.post().uri("/process").contentType(MediaType.APPLICATION_OCTET_STREAM).body(photo)
                .exchange((request, response) -> {
                    String text = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    int status = response.getStatusCode().value();
                    if (status >= 400 && status < 500 && status != 401) {
                        JsonNode problem = read(text);
                        throw new PhotoRejectedException(problem.path("code").asText("REJECTED"),
                                problem.path("message").asText("That photo can't be used."), status);
                    }
                    if (status != 200) {
                        throw new IllegalStateException("imaging service answered " + status);
                    }
                    return text;
                });
        JsonNode json = read(body);
        List<Variant> variants = new ArrayList<>();
        for (JsonNode v : json.path("variants")) {
            variants.add(new Variant(v.path("width").asInt(), v.path("actualWidth").asInt(), v.path("height").asInt(),
                    Base64.getDecoder().decode(v.path("webp").asText())));
        }
        return new Processed(json.path("width").asInt(), json.path("height").asInt(), variants);
    }

    private static JsonNode read(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException("imaging service sent something that isn't JSON", e);
        }
    }
}
