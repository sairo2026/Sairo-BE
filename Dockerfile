# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.13.1_2-jdk@sha256:cc9c4fb30bc63de16c227ac3c69f237a10537449ed7ab29fae21c6441470f69c AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d
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
