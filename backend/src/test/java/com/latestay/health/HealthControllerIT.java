package com.latestay.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.latestay.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class HealthControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void returnsOkWithPostgisVersion() {
        ResponseEntity<HealthResponse> response = restTemplate.getForEntity("/api/health", HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("ok");
        assertThat(response.getBody().postgis()).isNotBlank();
    }
}
