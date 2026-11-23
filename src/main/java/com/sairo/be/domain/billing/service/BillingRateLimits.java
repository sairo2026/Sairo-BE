package com.sairo.be.domain.billing.service;

import com.sairo.be.global.support.RateLimitRule;
import java.time.Duration;

public final class BillingRateLimits {

  public static final RateLimitRule PUBLIC_PRICING_IP =
      RateLimitRule.of("billing-public-pricing-ip", 60, Duration.ofSeconds(60));

  public static final RateLimitRule GRANT_AUTH_IP =
      RateLimitRule.of("billing-grant-auth-ip", 30, Duration.ofSeconds(60));

  /** 권한 ID와 접속지 조합별 비밀번호 실패 한도다. 넘으면 그 접속지만 잠시 거절하고 다른 접속지의 정상 권한자는 영향받지 않는다. */
  public static final RateLimitRule GRANT_AUTH_FAILURE_IP =
      RateLimitRule.of("billing-grant-auth-failure-ip", 5, Duration.ofMinutes(15));

  /** 권한 ID 하나에 접속지와 무관하게 쌓인 비밀번호 실패 수다. 넘으면 잠그지 않고 시도 간격만 늘린다. */
  public static final RateLimitRule GRANT_AUTH_FAILURE_GLOBAL =
      RateLimitRule.of("billing-grant-auth-failure-global", 30, Duration.ofMinutes(15));

  /** 전체 실패가 한도를 넘은 권한 ID에 적용하는 시도 간격이다. 간격마다 한 번만 비밀번호를 확인한다. */
  public static final RateLimitRule GRANT_AUTH_PACE =
      RateLimitRule.of("billing-grant-auth-pace", 1, Duration.ofSeconds(20));

  private BillingRateLimits() {}
}
