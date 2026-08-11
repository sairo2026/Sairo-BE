# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk@sha256:efd34b940f2d5a621605c8531c2afb7759c936b6c2ef637a69aa3bf3e1e789d1 AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre@sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system sairo \
    && useradd --system --gid sairo --no-create-home --shell /usr/sbin/nologin sairo
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN chown sairo:sairo /app/app.jar
USER sairo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
