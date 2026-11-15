package com.sairo.be.domain.billing.service;

import com.sairo.be.global.config.BillingProperties;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class LiveChargingGuard {

  private final boolean liveKey;
  private final BillingProperties billingProperties;

  public LiveChargingGuard(
      BillingProperties billingProperties,
      @Value("${sairo.toss.secret-key:}") String tossSecretKey) {
    this.billingProperties = billingProperties;
    this.liveKey = tossSecretKey != null && tossSecretKey.startsWith("live_sk_");
  }

  public boolean canCharge() {
    return !liveKey || billingProperties.chargingStarted();
  }

  public void requireChargeAllowed() {
    if (!canCharge()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }
}
