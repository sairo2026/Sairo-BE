package com.sairo.be.global.config;

import com.sairo.be.global.support.TextCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("!migrate")
@Configuration
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfig {

  @Bean("billingKeyCipher")
  TextCipher billingKeyCipher(BillingProperties properties) {
    return new TextCipher(properties.keyEncryptionSecret());
  }
}
