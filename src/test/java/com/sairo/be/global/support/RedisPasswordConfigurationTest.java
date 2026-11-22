package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisPasswordConfigurationTest {

  private static final String PASSWORD = "unit-test-redis-password-0123456789abcdef";

  @Container
  static final GenericContainer<?> PROTECTED_REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
          .withCommand("redis-server", "--requirepass", PASSWORD)
          .withExposedPorts(6379);

  private ApplicationContextRunner runner(String... extra) {
    return new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
        .withPropertyValues(
            "REDIS_HOST=" + PROTECTED_REDIS.getHost(),
            "REDIS_PORT=" + PROTECTED_REDIS.getMappedPort(6379))
        .withPropertyValues(extra);
  }

  @Test
  void connectsWhenRedisPasswordIsSupplied() {
    runner("REDIS_PASSWORD=" + PASSWORD)
        .run(
            context -> {
              StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
              template.opsForValue().set("password-config-test", "ok");
              assertThat(template.opsForValue().get("password-config-test")).isEqualTo("ok");
            });
  }

  @Test
  void isRejectedByProtectedRedisWhenPasswordIsMissing() {
    runner()
        .run(
            context -> {
              StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
              org.assertj.core.api.Assertions.assertThatThrownBy(
                      () -> template.opsForValue().get("password-config-test"))
                  .hasStackTraceContaining("NOAUTH");
            });
  }

  @Test
  void emptyPasswordMeansNoAuthentication() {
    runner()
        .run(
            context ->
                assertThat(context.getEnvironment().getProperty("spring.data.redis.password"))
                    .isEmpty());
  }
}
