package com.sairo.be.global.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sairo.billing")
public record BillingProperties(
    int vatRateBp,
    Duration customerKeyTtl,
    boolean chargingStarted,
    String keyEncryptionSecret,
    String pricingImageUrl) {}
