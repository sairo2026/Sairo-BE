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

  @Column(name = "reviewed_by_operator_id")
  private Long reviewedByOperatorId;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "pg_idempotency_key", length = 36)
  private String pgIdempotencyKey;

  @Column(name = "pg_refund_id", length = 200)
  private String pgRefundId;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

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

  public void recordImmediateEnd(long remainingSeconds, Instant effectiveEndAt) {
    this.remainingPeriodSeconds = remainingSeconds;
    this.effectiveEndAt = effectiveEndAt;
  }

  public boolean hasCalculation() {
    return originalApprovedAmount != null;
  }

  /** 접수 때 고정한 산정값을 승인 시점의 예약액과 환불 가능 잔액으로 다시 맞춘다. */
  public void adjustForApproval(long reservedAmount, long refundAmount) {
    this.reservedAmount = reservedAmount;
    this.refundAmount = refundAmount;
  }

  public boolean isAwaitingReview() {
    return status == RefundStatus.RECEIVED || status == RefundStatus.REVIEWING;
  }

  public void fillReasonEvent(Long eventId) {
    this.reasonEventId = eventId;
  }

  public void approve(long operatorId, Instant now) {
    this.status = RefundStatus.APPROVED;
    this.reviewedByOperatorId = operatorId;
    this.reviewedAt = now;
  }

  public void reject(long operatorId, String reason, Instant now) {
    this.status = RefundStatus.REJECTED;
    this.reviewedByOperatorId = operatorId;
    this.reviewedAt = now;
    this.rejectionReason = reason;
  }

  /** PG 취소 요청 직전이다. 멱등키는 처음 한 번만 정하고 확인 중 재요청에도 같은 키를 쓴다. */
  public void beginProcessing(String idempotencyKey) {
    if (this.pgIdempotencyKey == null) {
      this.pgIdempotencyKey = idempotencyKey;
    }
    this.status = RefundStatus.PROCESSING;
  }

  public void markCompleted(String pgRefundId, Instant now) {
    this.status = RefundStatus.COMPLETED;
    this.pgRefundId = pgRefundId;
    this.resolvedAt = now;
    this.failureReason = null;
  }

  public void markFailed(String reason, Instant now) {
    this.status = RefundStatus.FAILED;
    this.failureReason = reason;
    this.resolvedAt = now;
  }

  public void markVerifying(String pgRefundId) {
    this.status = RefundStatus.VERIFYING;
    if (pgRefundId != null) {
      this.pgRefundId = pgRefundId;
    }
  }
}
