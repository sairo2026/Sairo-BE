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
    Calculation calculation,
    @Schema(description = "신청 일시") OffsetDateTime createdAt) {

  public record Calculation(
      long originalApprovedAmount,
      @Schema(description = "청구 기간 전체 초. 청구 기간이 없는 일회성 청구서는 null", nullable = true)
          Long totalPeriodSeconds,
      Long remainingPeriodSeconds,
      long couponExcludedAmount,
      long priorCompensationAmount,
      OffsetDateTime effectiveEndAt) {}
}
