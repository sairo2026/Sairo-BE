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
# This container starts Java directly; the base image's Pebble supervisor is unused.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && apt-get install -y --only-upgrade --no-install-recommends \
      libkrb5support0 libkrb5-3 libk5crypto3 libgssapi-krb5-2 libexpat1 perl-base \
    && for package_name in libkrb5support0 libkrb5-3 libk5crypto3 libgssapi-krb5-2; do \
      dpkg --compare-versions "$(dpkg-query -W -f='${Version}' "${package_name}")" ge 1.22.1-2ubuntu4.3 || exit 1; \
    done \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libexpat1)" ge 2.7.4-1ubuntu0.3 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' perl-base)" ge 5.40.1-7ubuntu0.4 \
    && rm -f /usr/bin/pebble \
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
