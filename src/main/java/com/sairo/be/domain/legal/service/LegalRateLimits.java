package com.sairo.be.domain.legal.service;

import com.sairo.be.global.support.RateLimitRule;
import java.time.Duration;

public final class LegalRateLimits {

  public static final RateLimitRule PUBLIC_COMPANY_INFO_IP =
      RateLimitRule.of("legal-public-company-info-ip", 60, Duration.ofSeconds(60));

  private LegalRateLimits() {}
}
