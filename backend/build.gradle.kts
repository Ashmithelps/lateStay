plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.latestay"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("io.sentry:sentry-spring-boot-starter-jakarta:8.60.0")
    implementation("io.sentry:sentry-logback:8.60.0")
    // Hotel photos in S3-compatible storage (Cloudflare R2 by default).
    implementation(platform("software.amazon.awssdk:bom:2.39.6"))
    implementation("software.amazon.awssdk:s3")
    // One run per scheduled job across instances (deploy overlap, future scaling).
    implementation("net.javacrumbs.shedlock:shedlock-spring:6.9.2")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.9.2")
    implementation("com.google.firebase:firebase-admin:9.11.0")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("net.jqwik:jqwik:1.9.3")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// One jar with a stable name for the Docker build (no "-plain" jar, no version in the name).
tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveFileName = "latestay-backend.jar"
}

tasks.test {
    useJUnitPlatform {
        excludeTags("llm")
    }
    // Tests must not pick up a sourced backend/.env (the local profile, a real LLM key).
    listOf("SPRING_PROFILES_ACTIVE", "LLM_PROVIDER", "LLM_MODEL", "LLM_API_KEY", "LLM_BASE_URL")
        .forEach { environment.remove(it) }
}

// Live LLM tests: ./gradlew llmTest (needs LLM_PROVIDER and LLM_API_KEY; skipped otherwise).
tasks.register<Test>("llmTest") {
    description = "Runs tests tagged 'llm' against the configured LLM provider."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("llm")
    }
    outputs.upToDateWhen { false }
}
