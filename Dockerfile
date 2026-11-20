# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1 AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre@sha256:ca73b4d3961703d6d7830a4361f49ef0478f8f28b39defc07bb72f4cc7f834f6
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system sairo \
    && useradd --system --gid sairo --no-create-home --shell /usr/sbin/nologin sairo
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN chown sairo:sairo /app/app.jar \
    && mkdir -p /var/sairo/bootstrap \
    && chown sairo:sairo /var/sairo/bootstrap \
    && chmod 700 /var/sairo/bootstrap
USER sairo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
