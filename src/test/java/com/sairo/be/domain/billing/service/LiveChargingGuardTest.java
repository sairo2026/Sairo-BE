package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.config.BillingProperties;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LiveChargingGuardTest {

  private static BillingProperties properties(boolean started) {
    return new BillingProperties(1000, Duration.ofMinutes(15), started, "", "");
  }

  @Test
  void testKeyCanChargeBeforeLiveLaunch() {
    assertThat(new LiveChargingGuard(properties(false), "test_sk_example").canCharge()).isTrue();
  }

  @Test
  void liveKeyRequiresExplicitLaunchSwitch() {
    LiveChargingGuard guard = new LiveChargingGuard(properties(false), "live_sk_example");

    assertThat(guard.canCharge()).isFalse();
    assertThatThrownBy(guard::requireChargeAllowed)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(new LiveChargingGuard(properties(true), "live_sk_example").canCharge()).isTrue();
  }
}
