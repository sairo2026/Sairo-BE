package com.sairo.be.domain.contract.entity;

import java.time.LocalDate;

/** 임대기간 표시값이다. 날짜로만 정해지며 계약 상태를 바꾸지 않는다. */
public enum LeasePeriodStatus {
  BEFORE_START,
  IN_PROGRESS,
  MATURITY_CONFIRMATION_NEEDED;

  public static LeasePeriodStatus of(LocalDate start, LocalDate end, LocalDate today) {
    if (today.isBefore(start)) {
      return BEFORE_START;
    }
    return today.isAfter(end) ? MATURITY_CONFIRMATION_NEEDED : IN_PROGRESS;
  }
}
