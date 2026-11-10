package com.sairo.be.domain.billing.service;

import com.sairo.be.global.support.TimeRules;
import java.time.Duration;
import java.time.Instant;

public final class BillingPeriods {

  public static final Duration RETURN_PERIOD = Duration.ofDays(30);
  public static final Duration PAST_DUE_RESTRICT_AFTER = Duration.ofDays(8);
  public static final Duration PAST_DUE_TERMINATE_AFTER = Duration.ofDays(30);
  public static final Duration PRE_PAYMENT_LIMIT = Duration.ofDays(30);

  private BillingPeriods() {}

  public static Instant boundary(Instant anchor, int monthsAfterAnchor) {
    return TimeRules.toDisplay(anchor).plusMonths(monthsAfterAnchor).toInstant();
  }

  public static Instant returnPeriodEnd(Instant endedAt) {
    return endedAt.plus(RETURN_PERIOD);
  }

  public static boolean isInReturnPeriod(Instant endedAt, Instant now) {
    return endedAt != null && now.isBefore(returnPeriodEnd(endedAt));
  }
}
