package com.sairo.be.domain.billing.entity;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
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
@Table(name = "pricing_change_notice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceChangeNotice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "subscription_id", nullable = false, updatable = false)
  private Long subscriptionId;

  @Column(name = "from_price_schedule_id", nullable = false, updatable = false)
  private Long fromPriceScheduleId;

  @Column(name = "to_price_schedule_id", nullable = false, updatable = false)
  private Long toPriceScheduleId;

  @Enumerated(EnumType.STRING)
  @Column(name = "change_kind", nullable = false, length = 12, updatable = false)
  private PriceChangeKind changeKind;

  @Column(name = "member_count_snapshot", nullable = false, updatable = false)
  private int memberCountSnapshot;

  @Column(name = "current_amount_incl_vat", nullable = false, updatable = false)
  private long currentAmountInclVat;

  @Column(name = "new_amount_incl_vat", nullable = false, updatable = false)
  private long newAmountInclVat;

  @Column(name = "notified_at", nullable = false, updatable = false)
  private Instant notifiedAt;

  @Column(name = "effective_at", nullable = false, updatable = false)
  private Instant effectiveAt;

  @Column(name = "applies_from_period_start", nullable = false, updatable = false)
  private Instant appliesFromPeriodStart;

  @Column(name = "response_due_at", nullable = false, updatable = false)
  private Instant responseDueAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private PriceChangeDecision decision;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "decided_by_membership_id")
  private Long decidedByMembershipId;

  @Column(name = "purchase_consent_id")
  private Long purchaseConsentId;

  public boolean isConsentRequired() {
    return changeKind == PriceChangeKind.INCREASE;
  }

  public boolean isOpen(Instant now) {
    return decision == PriceChangeDecision.PENDING && now.isBefore(responseDueAt);
  }

  public void requireOpen(Instant now) {
    if (decision == PriceChangeDecision.NOT_REQUIRED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (!isOpen(now)) {
      throw new BusinessException(ErrorCode.PRICE_CHANGE_NOTICE_CLOSED);
    }
  }

  public void agree(Long membershipId, Long consentId, Instant now) {
    requireOpen(now);
    this.decision = PriceChangeDecision.AGREED;
    this.decidedAt = now;
    this.decidedByMembershipId = membershipId;
    this.purchaseConsentId = consentId;
  }

  public void decline(Long membershipId, Instant now) {
    requireOpen(now);
    this.decision = PriceChangeDecision.DECLINED;
    this.decidedAt = now;
    this.decidedByMembershipId = membershipId;
  }
}
