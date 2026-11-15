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
  private static final String CRON_PROPERTY = "sairo.batch.lease-period-scan.cron";

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

  @Test
  void 만기_스캔_주기는_기본으로_매일_자정이다() throws IOException {
    assertThat(prodEnvironment(Map.of()).getProperty(CRON_PROPERTY)).isEqualTo("0 0 0 * * *");
  }

  @Test
  void 환경변수로_만기_스캔_주기를_바꿀_수_있다() throws IOException {
    assertThat(
            prodEnvironment(Map.of("SAIRO_LEASE_PERIOD_SCAN_CRON", "0 */10 * * * *"))
                .getProperty(CRON_PROPERTY))
        .isEqualTo("0 */10 * * * *");
  }

  @Test
  void 메일_로고_주소는_기본으로_서비스_주소의_정적_파일이다() throws IOException {
    assertThat(prodEnvironment(Map.of()).getProperty("sairo.delivery.mail.logo-url"))
        .isEqualTo("https://app.sairo.agency/email/sairo-logo.png");
  }
}
