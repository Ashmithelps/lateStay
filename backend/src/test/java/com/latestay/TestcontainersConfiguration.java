package com.latestay;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostGIS container shared by every Spring test context in the run. Tests share the database, so each test
 * creates its own fixtures (unique slugs, its own map area) and never assumes empty tables.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres");

    // Spring caches many test contexts, each with its own connection pool, plus TwoInstancesIT starts two more
    // apps: Postgres's default of 100 connections runs out on CI.
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withCommand("postgres", "-c", "max_connections=300");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return POSTGIS;
    }
}
