package com.sairo.be.domain.visit.service;

import com.sairo.be.global.support.TimeRules;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

public final class CustomerLinkExpiry {

  static final Duration MAX_LIFETIME = Duration.ofDays(7);
  static final Duration RESPONSE_LEAD = Duration.ofHours(24);
  static final Duration SHORT_LIFETIME = Duration.ofHours(6);

  private CustomerLinkExpiry() {}

  public static Instant expiresAt(Instant issuedAt, LocalDate periodStartDate) {
    Instant periodStart = TimeRules.startOfDisplayDay(periodStartDate);
    Instant earliestStart = periodStart.isAfter(issuedAt) ? periodStart : issuedAt;
    if (!earliestStart.isAfter(issuedAt.plus(RESPONSE_LEAD))) {
      return issuedAt.plus(SHORT_LIFETIME);
    }
    Instant deadline = earliestStart.minus(RESPONSE_LEAD);
    Instant maxExpiry = issuedAt.plus(MAX_LIFETIME);
    return deadline.isBefore(maxExpiry) ? deadline : maxExpiry;
  }
}
