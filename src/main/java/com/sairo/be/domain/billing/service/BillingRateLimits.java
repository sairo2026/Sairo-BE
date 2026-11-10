package com.sairo.be.domain.billing.service;

import com.sairo.be.global.support.RateLimitRule;
import java.time.Duration;

public final class BillingRateLimits {

  public static final RateLimitRule PUBLIC_PRICING_IP =
      RateLimitRule.of("billing-public-pricing-ip", 60, Duration.ofSeconds(60));

  public static final RateLimitRule GRANT_AUTH_IP =
      RateLimitRule.of("billing-grant-auth-ip", 30, Duration.ofSeconds(60));

  private BillingRateLimits() {}
}
