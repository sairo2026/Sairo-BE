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
@Table(name = "billing_charge_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingChargeAttempt {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "invoice_id", nullable = false, updatable = false)
  private Long invoiceId;

  @Column(name = "attempt_no", nullable = false, updatable = false)
  private int attemptNo;

  @Column(name = "attempted_at", nullable = false, updatable = false)
  private Instant attemptedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private AttemptResult result;

  @Column(name = "failure_reason", columnDefinition = "text")
  private String failureReason;

  @Enumerated(EnumType.STRING)
  @Column(name = "failure_category", length = 20)
  private FailureCategory failureCategory;

  @Column(name = "pg_transaction_id", length = 200)
  private String pgTransactionId;

  @Column(name = "pg_order_id", nullable = false, length = 64, updatable = false)
  private String pgOrderId;

  @Column(name = "idempotency_key", nullable = false, length = 36, updatable = false)
  private String idempotencyKey;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  public static BillingChargeAttempt begin(
      Long invoiceId, int attemptNo, String pgOrderId, String idempotencyKey, Instant now) {
    BillingChargeAttempt attempt = new BillingChargeAttempt();
    attempt.invoiceId = invoiceId;
    attempt.attemptNo = attemptNo;
    attempt.pgOrderId = pgOrderId;
    attempt.idempotencyKey = idempotencyKey;
    attempt.attemptedAt = now;
    attempt.result = AttemptResult.PROCESSING;
    return attempt;
  }

  public void markSuccess(String paymentKey, Instant now) {
    this.result = AttemptResult.SUCCESS;
    this.pgTransactionId = paymentKey;
    this.resolvedAt = now;
    this.failureCategory = null;
    this.failureReason = null;
  }

  public void markFailed(FailureCategory category, String reason, Instant now) {
    this.result = AttemptResult.FAILED;
    this.failureCategory = category;
    this.failureReason = reason;
    this.resolvedAt = now;
  }

  public void markVerifying(String paymentKey) {
    this.result = AttemptResult.VERIFYING;
    if (paymentKey != null) {
      this.pgTransactionId = paymentKey;
    }
  }

  public boolean isInFlight() {
    return result == AttemptResult.PROCESSING || result == AttemptResult.VERIFYING;
  }
}
