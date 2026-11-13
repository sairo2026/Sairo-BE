package com.sairo.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ProdBatchPropertyTest {

  private static final String PROPERTY = "sairo.batch.enabled";

  private static StandardEnvironment prodEnvironment(Map<String, Object> overrides)
      throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
    for (PropertySource<?> source :
        new YamlPropertySourceLoader()
            .load("application-prod.yml", new ClassPathResource("application-prod.yml"))) {
      environment.getPropertySources().addLast(source);
    }
    return environment;
  }

  @Test
  void 운영_프로필은_예약_배치를_기본으로_켠다() throws IOException {
    assertThat(prodEnvironment(Map.of()).getProperty(PROPERTY)).isEqualTo("true");
  }

  @Test
  void 환경변수로_예약_배치를_끌_수_있다() throws IOException {
    assertThat(prodEnvironment(Map.of("SAIRO_BATCH_ENABLED", "false")).getProperty(PROPERTY))
        .isEqualTo("false");
  }
}
