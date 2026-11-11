package com.sairo.be.domain.support.service;

import com.sairo.be.global.support.RateLimitRule;
import java.time.Duration;

public final class SupportRateLimits {

  public static final RateLimitRule ENTERPRISE_LEAD_CREATE_IP =
      RateLimitRule.of("support-enterprise-lead-create-ip", 5, Duration.ofHours(1));
  public static final RateLimitRule ENTERPRISE_LEAD_ACCESS_IP =
      RateLimitRule.of("support-enterprise-lead-access-ip", 60, Duration.ofSeconds(60));
  public static final RateLimitRule ENTERPRISE_LEAD_ACCESS_LEAD =
      RateLimitRule.of("support-enterprise-lead-access-lead", 20, Duration.ofSeconds(60));

  private SupportRateLimits() {}
}
