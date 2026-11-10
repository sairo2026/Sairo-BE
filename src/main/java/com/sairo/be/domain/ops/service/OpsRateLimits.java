package com.sairo.be.domain.ops.service;

import com.sairo.be.global.support.RateLimitRule;
import java.time.Duration;

public final class OpsRateLimits {

  public static final RateLimitRule PUBLIC_IP =
      RateLimitRule.of("ops-public-ip", 30, Duration.ofSeconds(60));
  public static final RateLimitRule INVITATION_ACCEPT =
      RateLimitRule.of("ops-invitation-accept", 5, Duration.ofSeconds(60));

  private OpsRateLimits() {}
}
