package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.config.BillingProperties;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LiveChargingGuardTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);

  private static BillingProperties properties(boolean started) {
    return new BillingProperties(1000, Duration.ofMinutes(15), started, "", "");
  }

  private static LiveChargingGuard guard(boolean started, String key) {
    return new LiveChargingGuard(properties(started), key, CLOCK);
  }

  @ParameterizedTest
  @ValueSource(strings = {"live_sk_example", "live_gsk_example", " live_sk_example", "sk_example"})
  void liveKeyRequiresExplicitLaunchSwitch(String key) {
    LiveChargingGuard blocked = guard(false, key);

    assertThat(blocked.canCharge()).isFalse();
    assertThatThrownBy(blocked::requireChargeAllowed)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(guard(true, key).canCharge()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"test_sk_example", "test_gsk_example", " test_sk_example", "", "   "})
  @NullSource
  void testOrUnconfiguredKeyCanChargeRegardlessOfSwitch(String key) {
    assertThat(guard(false, key).canCharge()).isTrue();
    assertThat(guard(true, key).canCharge()).isTrue();
    guard(false, key).requireChargeAllowed();
  }

  @Test
  void scheduledRunIsNotBlockedWhenChargingAllowed() {
    assertThat(guard(false, "test_sk_example").isBlockedForScheduledRun("billing-renewal"))
        .isFalse();
    assertThat(guard(true, "live_sk_example").isBlockedForScheduledRun("billing-renewal"))
        .isFalse();
  }

  @Test
  void scheduledRunIsBlockedWhenLiveKeyWithoutSwitch() {
    assertThat(guard(false, "live_sk_example").isBlockedForScheduledRun("billing-renewal"))
        .isTrue();
  }
}
