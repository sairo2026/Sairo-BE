package com.sairo.be.domain.auth.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

@Profile("!migrate")
@Configuration
@EnableConfigurationProperties(KakaoProperties.class)
public class KakaoAuthConfig {

  @Bean
  RestClient.Builder restClientBuilder() {
    return RestClient.builder();
  }
}
