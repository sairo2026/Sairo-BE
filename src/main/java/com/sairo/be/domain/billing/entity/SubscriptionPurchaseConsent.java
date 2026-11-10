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
@Table(name = "subscription_purchase_consent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionPurchaseConsent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "subscription_id", nullable = false, updatable = false)
  private Long subscriptionId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(name = "terms_agreement_id", nullable = false, updatable = false)
  private Long termsAgreementId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12, updatable = false)
  private SubscriptionPlan plan;

  @Column(name = "member_count", nullable = false, updatable = false)
  private int memberCount;

  @Column(name = "total_amount_incl_vat", nullable = false, updatable = false)
  private long totalAmountInclVat;

  @Column(name = "vat_amount", nullable = false, updatable = false)
  private long vatAmount;

  @Column(name = "amount_limit", nullable = false, updatable = false)
  private long amountLimit;

  @Column(name = "period_start", nullable = false, updatable = false)
  private Instant periodStart;

  @Column(name = "period_end", nullable = false, updatable = false)
  private Instant periodEnd;

  @Column(name = "next_charge_at", nullable = false, updatable = false)
  private Instant nextChargeAt;

  @Column(nullable = false, updatable = false)
  private boolean agreed;

  @Column(name = "recorded_at", nullable = false, updatable = false)
  private Instant recordedAt;

  public static SubscriptionPurchaseConsent record(
      Long subscriptionId,
      Long userId,
      Long termsAgreementId,
      SubscriptionPlan plan,
      int memberCount,
      long totalAmountInclVat,
      long vatAmount,
      long amountLimit,
      Instant periodStart,
      Instant periodEnd,
      Instant nextChargeAt,
      boolean agreed,
      Instant recordedAt) {
    SubscriptionPurchaseConsent consent = new SubscriptionPurchaseConsent();
    consent.subscriptionId = subscriptionId;
    consent.userId = userId;
    consent.termsAgreementId = termsAgreementId;
    consent.plan = plan;
    consent.memberCount = memberCount;
    consent.totalAmountInclVat = totalAmountInclVat;
    consent.vatAmount = vatAmount;
    consent.amountLimit = amountLimit;
    consent.periodStart = periodStart;
    consent.periodEnd = periodEnd;
    consent.nextChargeAt = nextChargeAt;
    consent.agreed = agreed;
    consent.recordedAt = recordedAt;
    return consent;
  }
}
