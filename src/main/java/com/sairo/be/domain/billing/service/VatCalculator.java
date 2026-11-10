package com.sairo.be.domain.billing.service;

import com.sairo.be.global.config.BillingProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class VatCalculator {

  private static final long BASIS = 10_000L;

  private final BillingProperties properties;

  public VatCalculator(BillingProperties properties) {
    this.properties = properties;
  }

  public int rateBp() {
    return properties.vatRateBp();
  }

  public long vatAmount(long totalInclVat) {
    return vatAmount(totalInclVat, properties.vatRateBp());
  }

  public static long vatAmount(long totalInclVat, int rateBp) {
    return Math.floorDiv(totalInclVat * rateBp, BASIS + rateBp);
  }

  public long supplyAmount(long totalInclVat) {
    return totalInclVat - vatAmount(totalInclVat);
  }
}
