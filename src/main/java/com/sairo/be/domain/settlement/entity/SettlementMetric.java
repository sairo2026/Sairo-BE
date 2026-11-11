package com.sairo.be.domain.settlement.entity;

import java.util.Arrays;
import java.util.Optional;

/** 월말결산 지표 이름이다. 18개 세부 값과 부가 표시값 3개이며 상세 내역의 metric 값과 같다. */
public enum SettlementMetric {
  propertyCountTotal,
  customerCountTotal,
  propertyCountNew,
  customerCountNew,
  firstInquiryCount,
  visitCompletedCount,
  visitCompletedPropertyCount,
  coordinationEndedCount,
  coordinationVisitCompletedCount,
  coordinationVisitIncompleteCount,
  contractSignedCount,
  contractSignedPropertyCount,
  leaseMaturityCount,
  leaseMaturityPropertyCount,
  agreedFeeTotal,
  actualFeeReceivedTotal,
  outstandingAmount,
  overcollectedAmount,
  propertyCountNewMigrated,
  customerCountNewMigrated,
  agreedFeeUndatedCount;

  public static Optional<SettlementMetric> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    return Arrays.stream(values()).filter(metric -> metric.name().equals(value)).findFirst();
  }
}
