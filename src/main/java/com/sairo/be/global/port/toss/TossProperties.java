package com.sairo.be.global.port.toss;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sairo.toss")
public record TossProperties(
    String secretKey,
    String webhookSecret,
    String baseUrl,
    Duration connectTimeout,
    Duration requestTimeout,
    Duration approvalTimeout) {

  public String effectiveWebhookSecret() {
    return webhookSecret == null || webhookSecret.isBlank() ? secretKey : webhookSecret;
  }
}
