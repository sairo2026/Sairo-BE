package com.sairo.be.domain.billing.service;

import com.sairo.be.global.config.BillingProperties;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Profile("!migrate")
@Component
public class LiveChargingGuard {

  private static final String TEST_KEY_PREFIX = "test_";
  private static final long SKIP_WARN_INTERVAL_MILLIS = Duration.ofHours(1).toMillis();

  private final boolean liveKey;
  private final BillingProperties billingProperties;
  private final Clock clock;
  private final AtomicLong lastSkipWarnMillis = new AtomicLong(Long.MIN_VALUE);

  public LiveChargingGuard(
      BillingProperties billingProperties,
      @Value("${sairo.toss.secret-key:}") String tossSecretKey,
      Clock clock) {
    this.billingProperties = billingProperties;
    this.clock = clock;
    String key = tossSecretKey == null ? "" : tossSecretKey.trim();
    this.liveKey = !key.isEmpty() && !key.startsWith(TEST_KEY_PREFIX);
  }

  @PostConstruct
  void warnWhenBlocked() {
    if (!canCharge()) {
      log.warn("라이브 결제 키가 설정되어 있으나 청구 시작 스위치가 꺼져 있어 모든 실결제가 차단됩니다.");
    }
  }

  public boolean canCharge() {
    return !liveKey || billingProperties.chargingStarted();
  }

  public boolean isBlockedForScheduledRun(String jobName) {
    if (canCharge()) {
      return false;
    }
    long now = clock.millis();
    long last = lastSkipWarnMillis.get();
    if ((last == Long.MIN_VALUE || now - last >= SKIP_WARN_INTERVAL_MILLIS)
        && lastSkipWarnMillis.compareAndSet(last, now)) {
      log.warn("청구 시작 스위치가 꺼져 있어 청구 작업을 건너뜁니다. job={}", jobName);
    }
    return true;
  }

  public void requireChargeAllowed() {
    if (!canCharge()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }
}
