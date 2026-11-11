package com.sairo.be.domain.visit.service;

import com.sairo.be.global.support.RateLimitRule;
import java.time.Duration;

public final class VisitRateLimits {

  public static final RateLimitRule PUBLIC_LINK_IP =
      RateLimitRule.of("visit-public-link-ip", 120, Duration.ofSeconds(60));
  public static final RateLimitRule PUBLIC_LINK_TOKEN =
      RateLimitRule.of("visit-public-link-token", 60, Duration.ofSeconds(60));

  public static final RateLimitRule REQUEST_SUBMIT_MEMBER =
      RateLimitRule.of("visit-request-submit-member", 20, Duration.ofSeconds(60));

  private VisitRateLimits() {}
}
