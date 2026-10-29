# syntax=docker/dockerfile:1
# LateStay backend: build the Spring Boot jar, then run it on a JRE as a non-root user.

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
# Dependencies first, so source changes don't re-download them.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test \
    && java -Djarmode=tools -jar build/libs/latestay-backend.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-noble
RUN groupadd --system latestay && useradd --system --gid latestay --home /app latestay
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER latestay
ENV SPRING_PROFILES_ACTIVE=prod \
    PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
