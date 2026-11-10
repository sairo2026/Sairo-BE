package com.sairo.be.domain.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refund_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundRequest {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "invoice_id", nullable = false, updatable = false)
  private Long invoiceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason_code", nullable = false, length = 30, updatable = false)
  private RefundReasonCode reasonCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason_event_type", nullable = false, length = 30)
  private RefundEventType reasonEventType;

  @Column(name = "reason_event_id")
  private Long reasonEventId;

  @Column(name = "requested_at", nullable = false, updatable = false)
  private Instant requestedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private RefundStatus status;

  @Column(name = "refund_amount")
  private Long refundAmount;

  @Column(name = "entitlement_amount")
  private Long entitlementAmount;

  @Column(name = "previous_refund_amount", nullable = false)
  private long previousRefundAmount;

  @Column(name = "reserved_amount", nullable = false)
  private long reservedAmount;

  @Column(name = "rejection_reason", columnDefinition = "text")
  private String rejectionReason;

  @Column(name = "failure_reason", columnDefinition = "text")
  private String failureReason;

  @Column(name = "original_approved_amount")
  private Long originalApprovedAmount;

  @Column(name = "total_period_seconds")
  private Long totalPeriodSeconds;

  @Column(name = "remaining_period_seconds")
  private Long remainingPeriodSeconds;

  @Column(name = "coupon_excluded_amount")
  private Long couponExcludedAmount;

  @Column(name = "prior_compensation_amount")
  private Long priorCompensationAmount;

  @Column(name = "effective_end_at")
  private Instant effectiveEndAt;

  public static RefundRequest receive(
      Long officeId,
      Long invoiceId,
      RefundReasonCode reasonCode,
      Long reasonEventId,
      Instant requestedAt) {
    RefundRequest request = new RefundRequest();
    request.officeId = officeId;
    request.invoiceId = invoiceId;
    request.reasonCode = reasonCode;
    request.reasonEventType = reasonCode.eventType();
    request.reasonEventId = reasonEventId;
    request.requestedAt = requestedAt;
    request.status = RefundStatus.RECEIVED;
    return request;
  }

  public void fixCalculation(
      long originalApprovedAmount,
      Long totalPeriodSeconds,
      long entitlementAmount,
      long previousRefundAmount,
      long reservedAmount,
      long refundAmount) {
    this.originalApprovedAmount = originalApprovedAmount;
    this.totalPeriodSeconds = totalPeriodSeconds;
    this.couponExcludedAmount = 0L;
    this.priorCompensationAmount = 0L;
    this.entitlementAmount = entitlementAmount;
    this.previousRefundAmount = previousRefundAmount;
    this.reservedAmount = reservedAmount;
    this.refundAmount = refundAmount;
  }

  public boolean hasCalculation() {
    return originalApprovedAmount != null;
  }
}
