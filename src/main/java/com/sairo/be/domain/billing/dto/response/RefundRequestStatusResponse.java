package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "환불 처리 상태 응답")
public record RefundRequestStatusResponse(
    Long refundId,
    Long invoiceId,
    String status,
    String reasonCode,
    Long entitlementAmount,
    long previousRefundAmount,
    long reservedAmount,
    Long refundAmount,
    String rejectionReason,
    String failureReason,
    Calculation calculation) {

  public record Calculation(
      long originalApprovedAmount,
      Long totalPeriodSeconds,
      Long remainingPeriodSeconds,
      long couponExcludedAmount,
      long priorCompensationAmount,
      OffsetDateTime effectiveEndAt) {}
}
