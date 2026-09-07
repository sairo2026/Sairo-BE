package com.sairo.be.domain.auth.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

// Excluded from the "migrate" profile like the rest of the Kakao login stack: that
// profile has no KAKAO_* environment variables available (see application-migrate.yml).
@Profile("!migrate")
@Configuration
@EnableConfigurationProperties(KakaoProperties.class)
public class KakaoAuthConfig {

  // Spring Boot 4's webmvc starter does not auto-configure a RestClient.Builder bean;
  // that lives in a separate autoconfigure module this project does not depend on.
  @Bean
  RestClient.Builder restClientBuilder() {
    return RestClient.builder();
  }
}
