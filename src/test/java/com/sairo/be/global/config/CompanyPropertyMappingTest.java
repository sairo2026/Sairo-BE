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

class CompanyPropertyMappingTest {

  private static StandardEnvironment prodEnvironment(Map<String, Object> environment)
      throws IOException {
    StandardEnvironment result = new StandardEnvironment();
    result.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    result.getPropertySources().addFirst(new MapPropertySource("environment", environment));
    for (String resource : new String[] {"application-prod.yml", "application.yml"}) {
      for (PropertySource<?> source :
          new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource))) {
        result.getPropertySources().addLast(source);
      }
    }
    return result;
  }

  @Test
  void 회사_제공_환경변수는_운영_설정에서_회사_정보_속성으로_연결된다() throws IOException {
    StandardEnvironment environment =
        prodEnvironment(
            Map.of(
                "COMPANY_CONTACT_PHONE", "phone-value",
                "COMPANY_ECOMMERCE_NOTICE", "notice-value",
                "COMPANY_SUPPORT_EMAIL", "support-value",
                "COMPANY_PRIVACY_CONTACT", "privacy-value",
                "COMPANY_PRIVACY_REQUEST_PATH", "path-value"));

    assertThat(environment.getProperty("sairo.company.contact-phone")).isEqualTo("phone-value");
    assertThat(environment.getProperty("sairo.company.ecommerce-notice")).isEqualTo("notice-value");
    assertThat(environment.getProperty("sairo.company.support-email")).isEqualTo("support-value");
    assertThat(environment.getProperty("sairo.company.privacy-contact")).isEqualTo("privacy-value");
    assertThat(environment.getProperty("sairo.company.privacy-request-path"))
        .isEqualTo("path-value");
  }

  @Test
  void 환경변수가_없으면_회사_제공_항목은_빈_값이다() throws IOException {
    StandardEnvironment environment = prodEnvironment(Map.of());

    assertThat(environment.getProperty("sairo.company.contact-phone")).isEmpty();
    assertThat(environment.getProperty("sairo.company.support-email")).isEmpty();
  }
}
